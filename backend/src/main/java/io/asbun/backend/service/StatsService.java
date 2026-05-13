package io.asbun.backend.service;

import io.asbun.backend.dto.ModelStatsDto;
import io.asbun.backend.dto.ModelStatsDto.DailyAvgStat;
import io.asbun.backend.dto.ModelStatsDto.ModelTimeStat;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final long TTL_MS = 60 * 60 * 1000L; // 1 hour

    private final StatsRepository statsRepository;
    private final StatsSseService statsSseService;

    public Optional<ModelStatsDto> getCachedStatsIfFresh() {
        return statsRepository.loadStats()
                .filter(s -> s.getComputedAt() != null &&
                        Instant.now().toEpochMilli() - Instant.parse(s.getComputedAt()).toEpochMilli() < TTL_MS);
    }

    @Async
    public void computeAndNotifyAsync() {
        log.info("Computing stats async...");
        ModelStatsDto stats = computeAndStore();
        statsSseService.broadcastStats(stats);
    }

    public ModelStatsDto getStats() {
        return getCachedStatsIfFresh()
                .orElseGet(() -> {
                    log.info("Stats cache miss — computing fresh stats");
                    return computeAndStore();
                });
    }

    public ModelStatsDto computeAndStore() {
        List<Recipe> recipes = statsRepository.scanAllRecipes();

        List<ModelTimeStat> imageModelStats = Arrays.stream(ImageModel.values())
                .map(model -> {
                    List<Long> times = recipes.stream()
                            .filter(r -> model == r.getImageModel() && r.getImageGenerationMs() != null)
                            .map(Recipe::getImageGenerationMs)
                            .collect(Collectors.toList());
                    double avg = times.isEmpty() ? 0 : times.stream().mapToLong(Long::longValue).average().orElse(0);
                    return new ModelTimeStat(model.name(), imageModelDisplayName(model), avg, times.size());
                })
                .collect(Collectors.toList());

        List<ModelTimeStat> textModelStats = Arrays.stream(BedrockModel.values())
                .map(model -> {
                    List<Long> times = recipes.stream()
                            .filter(r -> model == r.getModel() && r.getTextGenerationMs() != null)
                            .map(Recipe::getTextGenerationMs)
                            .collect(Collectors.toList());
                    double avg = times.isEmpty() ? 0 : times.stream().mapToLong(Long::longValue).average().orElse(0);
                    return new ModelTimeStat(model.name(), textModelDisplayName(model), avg, times.size());
                })
                .collect(Collectors.toList());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<LocalDate, List<Long>> byDay = new HashMap<>();
        for (int i = 0; i < 30; i++) {
            byDay.put(today.minusDays(i), new ArrayList<>());
        }
        recipes.stream()
                .filter(r -> r.getCreatedAt() != null && r.getImageGenerationMs() != null)
                .forEach(r -> {
                    LocalDate day = r.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
                    if (byDay.containsKey(day)) {
                        byDay.get(day).add(r.getImageGenerationMs());
                    }
                });

        List<DailyAvgStat> dailyImageAvg = IntStream.range(0, 30)
                .mapToObj(i -> today.minusDays(29 - i))
                .map(day -> {
                    List<Long> times = byDay.get(day);
                    double avg = times.isEmpty() ? 0 : times.stream().mapToLong(Long::longValue).average().orElse(0);
                    return new DailyAvgStat(day.toString(), avg, times.size());
                })
                .collect(Collectors.toList());

        ModelStatsDto stats = new ModelStatsDto(imageModelStats, textModelStats, dailyImageAvg, Instant.now().toString());
        statsRepository.saveStats(stats);
        return stats;
    }

    private String imageModelDisplayName(ImageModel model) {
        return switch (model) {
            case STABILITY_CORE -> "Stability AI Core";
            case GPT_IMAGE_1_5 -> "GPT Image 1.5";
            case GOOGLE_IMAGEN_4 -> "Google Imagen 4";
            case GOOGLE_IMAGEN_4_FAST -> "Imagen 4 Fast";
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
