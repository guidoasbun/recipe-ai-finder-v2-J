package io.asbun.backend.search;

import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import io.asbun.backend.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * In-memory catalog search. Loads the catalog from DynamoDB into a cache and ranks by
 * keyword score and/or embedding cosine similarity.
 *
 * <p>Memory: embeddings are cached as primitive {@code float[]} (not boxed
 * {@code List<Double>}). A 1,024-dim {@code float[]} is ~4 KB vs ~24 KB for the boxed form,
 * so the practical in-app ceiling stays well under the backend's memory budget. Even so,
 * keep the catalog in the low tens of thousands for the in-app backend; larger sets belong
 * on OpenSearch (see design.md §6.2 / §12).
 */
@Slf4j
@Component
public class InAppCatalogSearchService implements CatalogSearchService {

    private final CatalogRecipeRepository repository;
    private final EmbeddingService embeddingService;
    private final boolean semanticEnabled;
    private final String mode; // keyword | semantic | hybrid

    private final AtomicReference<List<CachedRecipe>> cache = new AtomicReference<>();

    public InAppCatalogSearchService(CatalogRecipeRepository repository,
                                     EmbeddingService embeddingService,
                                     @Value("${catalog.search.semantic-enabled:true}") boolean semanticEnabled,
                                     @Value("${catalog.search.mode:hybrid}") String mode) {
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.semanticEnabled = semanticEnabled;
        this.mode = mode;
    }

    private List<CachedRecipe> catalog() {
        List<CachedRecipe> current = cache.get();
        if (current == null) {
            List<CatalogRecipe> loaded = repository.findAll();
            List<CachedRecipe> built = new ArrayList<>(loaded.size());
            for (CatalogRecipe r : loaded) {
                built.add(new CachedRecipe(r, toFloatArray(r.getEmbedding())));
                // Release the boxed embedding so we do not retain both representations.
                r.setEmbedding(null);
            }
            current = built;
            cache.set(current);
            log.info("Loaded {} catalog recipes into in-app search cache", current.size());
        }
        return current;
    }

    private static float[] toFloatArray(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        float[] arr = new float[embedding.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = embedding.get(i).floatValue();
        }
        return arr;
    }

    /** Clears the cache; next search reloads from DynamoDB (used after ingestion). */
    public void refresh() {
        cache.set(null);
    }

    @Override
    public CatalogSearchResults search(CatalogSearchQuery query) {
        List<CachedRecipe> candidates = catalog().stream()
                .filter(r -> matchesDietary(r.recipe, query.dietaryTags()))
                .collect(Collectors.toList());

        boolean hasText = query.text() != null && !query.text().isBlank();

        List<Scored> scored;
        if (!hasText) {
            // Browse: no ranking signal, keep stable order.
            scored = candidates.stream()
                    .map(r -> new Scored(r, 0.0))
                    .collect(Collectors.toList());
        } else {
            scored = rank(candidates, query.text());
            // A text query is a filter, not just a sort: drop recipes that do not match.
            // In keyword mode "match" means a positive keyword score. In semantic/hybrid
            // mode, ranking already orders by relevance, but we still drop recipes with no
            // signal at all (guards against returning the entire catalog for a query).
            scored = scored.stream()
                    .filter(s -> s.score > 0.0)
                    .collect(Collectors.toList());
        }

        long total = scored.size();

        int pageSize = Math.max(1, query.pageSize());
        int page = Math.max(0, query.page());
        // Compute offset as long: page*pageSize with large page values would overflow int
        // and pass a negative argument to Stream.skip (500 instead of an empty page).
        long from = (long) page * pageSize;

        List<CatalogRecipeDto> items = scored.stream()
                .sorted(Comparator.comparingDouble((Scored s) -> s.score).reversed())
                .skip(from)
                .limit(pageSize)
                .map(s -> toDto(s.cached.recipe))
                .collect(Collectors.toList());

        return new CatalogSearchResults(items, page, pageSize, total);
    }

    private List<Scored> rank(List<CachedRecipe> candidates, String text) {
        float[] queryVector = null;
        boolean useSemantic = semanticEnabled && !"keyword".equalsIgnoreCase(mode);
        if (useSemantic) {
            try {
                queryVector = toFloatArray(embeddingService.embed(text));
            } catch (Exception e) {
                // Graceful fallback: semantic failed, use keyword only.
                log.warn("Query embedding failed, falling back to keyword search: {}", e.getMessage());
                queryVector = null;
            }
        }

        String[] terms = tokenize(text);
        List<Scored> result = new ArrayList<>(candidates.size());
        for (CachedRecipe c : candidates) {
            double keywordScore = keywordScore(c.recipe, terms);
            boolean keywordHit = keywordScore > 0.0;
            double semanticScore = (queryVector != null && c.embedding != null)
                    ? cosine(queryVector, c.embedding)
                    : 0.0;

            double score;
            if ("semantic".equalsIgnoreCase(mode) && queryVector != null) {
                // Pure semantic: rank by similarity, keep only reasonably-similar recipes.
                score = semanticScore >= SEMANTIC_MATCH_THRESHOLD ? semanticScore : 0.0;
            } else if (queryVector != null) {
                // Hybrid: a recipe matches if it has a keyword hit OR a strong semantic score.
                boolean semanticHit = semanticScore >= SEMANTIC_MATCH_THRESHOLD;
                if (keywordHit || semanticHit) {
                    // Clamp the semantic contribution at zero: cosine can be negative, and a
                    // negative value must never cancel out a real keyword match (which would
                    // then be filtered as score<=0 despite the keyword-hit OR semantic-hit rule).
                    double semanticContribution = Math.max(0.0, semanticScore);
                    score = 0.5 * semanticContribution + 0.5 * normalizeKeyword(keywordScore, terms.length);
                } else {
                    score = 0.0;
                }
            } else {
                // Keyword-only (mode=keyword, semantic disabled, or embedding failed).
                score = keywordScore;
            }
            result.add(new Scored(c, score));
        }
        return result;
    }

    /** Minimum cosine similarity for a recipe to count as a semantic match. */
    private static final double SEMANTIC_MATCH_THRESHOLD = 0.35;

    private boolean matchesDietary(CatalogRecipe r, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        List<String> tags = r.getDietaryTags();
        if (tags == null) {
            return false;
        }
        return tags.containsAll(requiredTags);
    }

    private String[] tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9]+"))
                .filter(t -> !t.isBlank())
                .toArray(String[]::new);
    }

    /** Term-frequency style score; title matches weighted higher than body matches. */
    private double keywordScore(CatalogRecipe r, String[] terms) {
        if (terms.length == 0) {
            return 0.0;
        }
        String title = r.getTitle() == null ? "" : r.getTitle().toLowerCase();
        String body = r.getSearchText() == null ? "" : r.getSearchText();
        double score = 0.0;
        for (String term : terms) {
            if (title.contains(term)) {
                score += 2.0;
            }
            if (body.contains(term)) {
                score += 1.0;
            }
        }
        return score;
    }

    private double normalizeKeyword(double rawScore, int termCount) {
        if (termCount == 0) {
            return 0.0;
        }
        // Max per term is 3.0 (title + body). Normalize into [0,1].
        double max = 3.0 * termCount;
        return Math.min(1.0, rawScore / max);
    }

    private double cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < n; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0.0 || nb == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    @Override
    public Optional<CatalogRecipeDto> findById(String catalogRecipeId) {
        return repository.findById(catalogRecipeId).map(this::toDto);
    }

    private CatalogRecipeDto toDto(CatalogRecipe r) {
        return CatalogRecipeDto.builder()
                .catalogRecipeId(r.getCatalogRecipeId())
                .title(r.getTitle())
                .description(r.getDescription())
                .ingredients(r.getIngredients())
                .steps(r.getSteps())
                .imageUrl(r.getImageUrl())
                .dietaryTags(r.getDietaryTags())
                .sourceName(r.getSourceName())
                .sourceUrl(r.getSourceUrl())
                .sourceLicense(r.getSourceLicense())
                .sourceCountry(r.getSourceCountry())
                .build();
    }

    private static final class Scored {
        final CachedRecipe cached;
        final double score;

        Scored(CachedRecipe cached, double score) {
            this.cached = cached;
            this.score = score;
        }
    }

    /** Cached catalog entry: the recipe plus its embedding as a compact primitive array. */
    private static final class CachedRecipe {
        final CatalogRecipe recipe;
        final float[] embedding;

        CachedRecipe(CatalogRecipe recipe, float[] embedding) {
            this.recipe = recipe;
            this.embedding = embedding;
        }
    }
}
