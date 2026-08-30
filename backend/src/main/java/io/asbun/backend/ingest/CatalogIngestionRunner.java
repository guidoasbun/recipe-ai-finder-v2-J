package io.asbun.backend.ingest;

import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One-off catalog ingestion. Runs ONLY when {@code catalog.ingest.enabled=true} so it never
 * executes during normal application boot. Loads both Phase 1 sources (TheMealDB xlsx export
 * + AllRecipes CSV), tags dietary restrictions, embeds via the synchronous strategy, and
 * persists to the catalog table.
 *
 * <p>Idempotent: the catalog id is a deterministic hash of the source id, and recipes that
 * already have an embedding are skipped, so re-running does not duplicate or re-embed.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.ingest.enabled", havingValue = "true")
public class CatalogIngestionRunner implements CommandLineRunner {

    private final CatalogRecipeRepository repository;
    private final DietaryTagger dietaryTagger;
    private final EmbeddingStrategy embeddingStrategy;
    private final Path sourceDir;

    public CatalogIngestionRunner(CatalogRecipeRepository repository,
                                  DietaryTagger dietaryTagger,
                                  EmbeddingStrategy embeddingStrategy,
                                  @Value("${catalog.ingest.source-dir}") String sourceDir) {
        this.repository = repository;
        this.dietaryTagger = dietaryTagger;
        this.embeddingStrategy = embeddingStrategy;
        this.sourceDir = Path.of(sourceDir);
    }

    @Override
    public void run(String... args) {
        List<RecipeSource> sources = new ArrayList<>();
        sources.add(new XlsxMealDbSource(sourceDir.resolveSibling("archive")));
        sources.add(new CsvBetterRecipesSource(sourceDir.resolveSibling("archive-1").resolve("recipes.csv")));

        int total = 0;
        int embedded = 0;
        int skipped = 0;
        int failed = 0;

        for (RecipeSource source : sources) {
            List<ParsedRecipe> parsed;
            try {
                parsed = source.load();
            } catch (Exception e) {
                log.error("Source {} failed to load: {}", source.name(), e.getMessage());
                continue;
            }
            log.info("Ingesting {} recipes from {}", parsed.size(), source.name());

            for (ParsedRecipe p : parsed) {
                total++;
                String catalogId = deterministicId(p.sourceId());

                // Skip if already embedded (resumable / idempotent).
                var existing = repository.findById(catalogId);
                if (existing.isPresent() && existing.get().getEmbedding() != null
                        && !existing.get().getEmbedding().isEmpty()) {
                    skipped++;
                    continue;
                }

                try {
                    List<String> tags = dietaryTagger.tag(p.ingredients());
                    String searchText = buildSearchText(p);
                    List<Double> vector = embeddingStrategy.embed(embeddingInput(p));

                    CatalogRecipe recipe = CatalogRecipe.builder()
                            .catalogRecipeId(catalogId)
                            .title(p.title())
                            .description(p.description())
                            .ingredients(p.ingredients())
                            .steps(p.steps())
                            .imageUrl(p.imageUrl())
                            .dietaryTags(tags)
                            .searchText(searchText)
                            .embedding(vector)
                            .sourceName(p.sourceName())
                            .sourceUrl(p.sourceUrl())
                            .sourceLicense(p.sourceLicense())
                            .sourceCountry(p.sourceCountry())
                            .ingestedAt(Instant.now())
                            .build();

                    repository.save(recipe);
                    embedded++;
                    if (embedded % 50 == 0) {
                        log.info("Progress: {} embedded, {} skipped, {} failed (of {} seen)",
                                embedded, skipped, failed, total);
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Failed to ingest '{}' from {}: {}", p.title(), source.name(), e.getMessage());
                }
            }
        }

        log.info("Ingestion complete: {} seen, {} embedded, {} skipped, {} failed",
                total, embedded, skipped, failed);
    }

    private String embeddingInput(ParsedRecipe p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.title());
        if (p.description() != null && !p.description().isBlank()) {
            sb.append(". ").append(p.description());
        }
        if (p.ingredients() != null && !p.ingredients().isEmpty()) {
            sb.append(". Ingredients: ").append(String.join(", ", p.ingredients()));
        }
        return sb.toString();
    }

    private String buildSearchText(ParsedRecipe p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.title() == null ? "" : p.title());
        if (p.description() != null) {
            sb.append(' ').append(p.description());
        }
        if (p.ingredients() != null) {
            sb.append(' ').append(String.join(" ", p.ingredients()));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private String deterministicId(String sourceId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sourceId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) { // 32 hex chars is plenty for uniqueness
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash source id", e);
        }
    }
}
