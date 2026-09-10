package io.asbun.backend.service;

import io.asbun.backend.dto.ModelStatsDto;
import io.asbun.backend.dto.ModelStatsDto.DailyAvgStat;
import io.asbun.backend.dto.ModelStatsDto.ModelTimeStat;
import io.asbun.backend.dto.StatsAggregate;
import io.asbun.backend.dto.StatsAggregate.Bucket;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import io.asbun.backend.repository.StatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    /** Number of trailing days retained in the daily image-latency chart. */
    private static final int DAILY_WINDOW_DAYS = 30;

    private final StatsRepository statsRepository;
    private final StatsSseService statsSseService;

    public Optional<ModelStatsDto> getCachedStatsIfFresh() {
        // The aggregate item is always current (every write folds into it atomically), so a fresh
        // load is always "fresh". Returns empty only when the item does not exist yet.
        return statsRepository.loadAggregate().map(this::toDto);
    }

    @Async
    public void computeAndNotifyAsync() {
        log.info("Computing stats async...");
        try {
            ModelStatsDto stats = computeAndStore();
            statsSseService.broadcastStats(stats);
        } catch (Exception e) {
            log.error("Stats computation failed", e);
            statsSseService.completeAllWithError();
        }
    }

    public ModelStatsDto getStats() {
        return getCachedStatsIfFresh()
                .orElseGet(() -> {
                    log.info("No stats aggregate yet — rebuilding from a full scan");
                    return computeAndStore();
                });
    }

    // ------------------------------------------------------------------------
    // Incremental O(1) updates — called on the write path.
    //
    // Each call issues a single atomic UpdateItem (ADD) against the aggregate item. DynamoDB
    // applies the increments server-side, so concurrent recipe/image creations compose correctly
    // with no read-modify-write and no application-side locking.
    // ------------------------------------------------------------------------

    /** Record a text-generation latency for {@code model}. O(1). Called when a recipe is created. */
    public void recordTextGeneration(BedrockModel model, Long textGenerationMs) {
        if (model == null || textGenerationMs == null) {
            return;
        }
        statsRepository.incrementTextModel(model.name(), textGenerationMs, Instant.now().toString());
        broadcastLatest();
    }

    /**
     * Record an image-generation latency for {@code imageModel}. Bumps both the per-model bucket
     * and today's daily bucket in one atomic UpdateItem. O(1). Called when an image finishes
     * generating.
     */
    public void recordImageGeneration(ImageModel imageModel, Long imageGenerationMs) {
        if (imageModel == null || imageGenerationMs == null) {
            return;
        }
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        statsRepository.incrementImageModel(imageModel.name(), today, imageGenerationMs, Instant.now().toString());
        broadcastLatest();
    }

    private void broadcastLatest() {
        // Best-effort push of the updated view to any SSE subscribers. A failed read/broadcast
        // must never fail the write path that triggered it.
        try {
            statsRepository.loadAggregate()
                    .map(this::toDto)
                    .ifPresent(statsSseService::broadcastStats);
        } catch (Exception e) {
            log.debug("Failed to broadcast updated stats", e);
        }
    }

    // ------------------------------------------------------------------------
    // Rebuild path — full scan, used only when no aggregate exists yet.
    // ------------------------------------------------------------------------

    public ModelStatsDto computeAndStore() {
        StatsAggregate agg = rebuildAggregateFromScan();
        statsRepository.overwriteAggregate(agg);
        return toDto(agg);
    }

    private StatsAggregate rebuildAggregateFromScan() {
        List<Recipe> recipes = statsRepository.scanAllRecipes();
        StatsAggregate agg = new StatsAggregate();

        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(DAILY_WINDOW_DAYS - 1L);

        for (Recipe r : recipes) {
            if (r.getModel() != null && r.getTextGenerationMs() != null) {
                agg.getTextModels().computeIfAbsent(r.getModel().name(), k -> new Bucket())
                        .add(r.getTextGenerationMs());
            }
            if (r.getImageModel() != null && r.getImageGenerationMs() != null) {
                agg.getImageModels().computeIfAbsent(r.getImageModel().name(), k -> new Bucket())
                        .add(r.getImageGenerationMs());
                if (r.getCreatedAt() != null) {
                    LocalDate day = r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    if (!day.isBefore(cutoff)) {
                        agg.getDailyImage().computeIfAbsent(day.toString(), k -> new Bucket())
                                .add(r.getImageGenerationMs());
                    }
                }
            }
        }
        agg.setUpdatedAt(Instant.now().toString());
        return agg;
    }

    // ------------------------------------------------------------------------
    // Derive the client-facing DTO from the aggregate. O(models + window).
    // ------------------------------------------------------------------------

    private ModelStatsDto toDto(StatsAggregate agg) {
        List<ModelTimeStat> imageModelStats = Arrays.stream(ImageModel.values())
                .map(model -> {
                    Bucket b = agg.getImageModels().getOrDefault(model.name(), new Bucket());
                    return new ModelTimeStat(model.name(), imageModelDisplayName(model), b.avg(), b.getCount());
                })
                .collect(Collectors.toList());

        List<ModelTimeStat> textModelStats = Arrays.stream(BedrockModel.values())
                .map(model -> {
                    Bucket b = agg.getTextModels().getOrDefault(model.name(), new Bucket());
                    return new ModelTimeStat(model.name(), textModelDisplayName(model), b.avg(), b.getCount());
                })
                .collect(Collectors.toList());

        // The daily window is applied at read time: we only surface the trailing DAILY_WINDOW_DAYS
        // even if older day attributes linger on the item.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DailyAvgStat> dailyImageAvg = IntStream.range(0, DAILY_WINDOW_DAYS)
                .mapToObj(i -> today.minusDays(DAILY_WINDOW_DAYS - 1L - i))
                .map(day -> {
                    Bucket b = agg.getDailyImage().getOrDefault(day.toString(), new Bucket());
                    return new DailyAvgStat(day.toString(), b.avg(), b.getCount());
                })
                .collect(Collectors.toList());

        String computedAt = agg.getUpdatedAt() != null ? agg.getUpdatedAt() : Instant.now().toString();
        return new ModelStatsDto(imageModelStats, textModelStats, dailyImageAvg, computedAt);
    }

    private String imageModelDisplayName(ImageModel model) {
        return switch (model) {
            case STABILITY_CORE -> "Stability AI Core";
            case GPT_IMAGE_1_5 -> "GPT Image 1.5";
            case GOOGLE_IMAGEN_4 -> "Nano Banana 2";
            case GOOGLE_IMAGEN_4_FAST -> "Nano Banana 2 Lite";
        };
    }

    private String textModelDisplayName(BedrockModel model) {
        return switch (model) {
            case CLAUDE_HAIKU -> "Claude Haiku";
            case CLAUDE_SONNET -> "Claude Sonnet";
            case AMAZON_TITAN -> "Amazon Nova Micro";
            case LLAMA3 -> "Llama 3";
            case NOVA_LITE -> "Nova Lite";
        };
    }
}
