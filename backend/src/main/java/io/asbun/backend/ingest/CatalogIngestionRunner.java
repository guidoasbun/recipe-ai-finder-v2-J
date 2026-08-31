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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
    private final SynchronousEmbeddingStrategy syncStrategy;
    private final BatchEmbeddingStrategy batchStrategy;
    private final Path sourceDir;
    private final String strategyName;
    private final String recipeNlgFile;
    private final int recipeNlgMaxRecords;

    public CatalogIngestionRunner(CatalogRecipeRepository repository,
                                  DietaryTagger dietaryTagger,
                                  SynchronousEmbeddingStrategy syncStrategy,
                                  BatchEmbeddingStrategy batchStrategy,
                                  @Value("${catalog.ingest.source-dir}") String sourceDir,
                                  @Value("${dynamodb.catalog-full-table:${dynamodb.catalog-table}}") String targetTable,
                                  @Value("${catalog.ingest.embedding-strategy:sync}") String strategyName,
                                  @Value("${catalog.ingest.recipenlg-file:}") String recipeNlgFile,
                                  @Value("${catalog.ingest.recipenlg-max-records:0}") int recipeNlgMaxRecords) {
        // Target the configured catalog table. Defaults to the small in-app table, so existing
        // ingestion is unchanged; set dynamodb.catalog-full-table to load the full dataset into
        // the separate table without touching the in-app table (rollback preservation).
        this.repository = repository.forTable(targetTable);
        this.dietaryTagger = dietaryTagger;
        this.syncStrategy = syncStrategy;
        this.batchStrategy = batchStrategy;
        this.sourceDir = Path.of(sourceDir);
        this.strategyName = strategyName;
        this.recipeNlgFile = recipeNlgFile;
        this.recipeNlgMaxRecords = recipeNlgMaxRecords;
    }

    @Override
    public void run(String... args) {
        log.info("Catalog ingestion target table: {}, embedding-strategy: {}",
                repository.tableName(), strategyName);
        List<RecipeSource> sources = new ArrayList<>();
        // RecipeNLG (Phase 2 / full 2.2M) when a file is configured; otherwise Phase 1 sources.
        if (recipeNlgFile != null && !recipeNlgFile.isBlank()) {
            int cap = recipeNlgMaxRecords > 0 ? recipeNlgMaxRecords : Integer.MAX_VALUE;
            sources.add(new RecipeNlgCsvSource(Path.of(recipeNlgFile), cap));
            log.info("RecipeNLG source enabled: file={}, cap={}", recipeNlgFile,
                    cap == Integer.MAX_VALUE ? "none (full set)" : cap);
        } else {
            sources.add(new XlsxMealDbSource(sourceDir.resolveSibling("archive")));
            sources.add(new CsvBetterRecipesSource(sourceDir.resolveSibling("archive-1").resolve("recipes.csv")));
        }

        if ("batch".equalsIgnoreCase(strategyName)) {
            runBatch(sources);
            return;
        }

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
                    List<Double> vector = syncStrategy.embed(embeddingInput(p));

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

    /**
     * Batch path for the full 2.2M load: collect all not-yet-embedded recipes, embed them in one
     * Bedrock Batch Inference job, then persist each with its returned vector. Idempotent —
     * already-embedded recipes are skipped, so a re-run only embeds the remainder.
     */
    private void runBatch(List<RecipeSource> sources) {
        Map<String, String> toEmbed = new LinkedHashMap<>();       // catalogId -> embedding input text
        Map<String, ParsedRecipe> byId = new LinkedHashMap<>();     // catalogId -> parsed recipe
        int seen = 0;
        int skipped = 0;

        for (RecipeSource source : sources) {
            List<ParsedRecipe> parsed;
            try {
                parsed = source.load();
            } catch (Exception e) {
                log.error("Source {} failed to load: {}", source.name(), e.getMessage());
                continue;
            }
            log.info("Preparing {} recipes from {} for batch embedding", parsed.size(), source.name());
            for (ParsedRecipe p : parsed) {
                seen++;
                String catalogId = deterministicId(p.sourceId());
                var existing = repository.findById(catalogId);
                if (existing.isPresent() && existing.get().getEmbedding() != null
                        && !existing.get().getEmbedding().isEmpty()) {
                    skipped++;
                    continue;
                }
                toEmbed.put(catalogId, embeddingInput(p));
                byId.put(catalogId, p);
            }
        }

        log.info("Batch embedding {} recipes ({} already embedded, skipped)", toEmbed.size(), skipped);
        if (toEmbed.isEmpty()) {
            log.info("Nothing to embed; batch ingestion complete.");
            return;
        }

        Map<String, List<Double>> vectors = batchStrategy.embedAll(toEmbed);

        int persisted = 0;
        int missing = 0;
        for (Map.Entry<String, ParsedRecipe> entry : byId.entrySet()) {
            String catalogId = entry.getKey();
            ParsedRecipe p = entry.getValue();
            List<Double> vector = vectors.get(catalogId);
            if (vector == null || vector.isEmpty()) {
                missing++;
                continue;
            }
            CatalogRecipe recipe = CatalogRecipe.builder()
                    .catalogRecipeId(catalogId)
                    .title(p.title())
                    .description(p.description())
                    .ingredients(p.ingredients())
                    .steps(p.steps())
                    .imageUrl(p.imageUrl())
                    .dietaryTags(dietaryTagger.tag(p.ingredients()))
                    .searchText(buildSearchText(p))
                    .embedding(vector)
                    .sourceName(p.sourceName())
                    .sourceUrl(p.sourceUrl())
                    .sourceLicense(p.sourceLicense())
                    .sourceCountry(p.sourceCountry())
                    .ingestedAt(Instant.now())
                    .build();
            repository.save(recipe);
            persisted++;
            if (persisted % 1000 == 0) {
                log.info("Persisted {} / {} embedded recipes", persisted, vectors.size());
            }
        }

        log.info("Batch ingestion complete: {} seen, {} skipped, {} persisted, {} missing-vector",
                seen, skipped, persisted, missing);
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
