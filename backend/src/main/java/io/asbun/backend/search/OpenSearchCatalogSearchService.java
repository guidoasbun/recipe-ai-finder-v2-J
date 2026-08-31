package io.asbun.backend.search;

import io.asbun.backend.config.OpenSearchProperties;
import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OpenSearch-backed catalog search. Satisfies the same {@link CatalogSearchService} contract
 * as {@link InAppCatalogSearchService}, translating a {@link CatalogSearchQuery} into an
 * OpenSearch query so behavior matches the in-app backend at scale:
 *
 * <ul>
 *   <li><b>Dietary filter</b> — a {@code terms}-per-tag filter requiring every requested tag
 *       (AND), mirroring {@code tags.containsAll(requiredTags)}.</li>
 *   <li><b>Keyword</b> — {@code multi_match} over title (boosted), description, ingredients.</li>
 *   <li><b>Semantic</b> — a {@code knn} query on the stored embedding, gated by
 *       {@code catalog.search.semantic-enabled} and {@code catalog.search.mode}.</li>
 *   <li><b>Hybrid</b> — keyword and knn combined as {@code should} clauses (score blend).</li>
 *   <li><b>Fallback</b> — if query embedding fails, drop the vector clause and run keyword
 *       only.</li>
 *   <li><b>Browse</b> — blank text returns a {@code match_all} listing (dietary filter still
 *       applied).</li>
 * </ul>
 *
 * <p>Only active when {@code catalog.search.backend=opensearch}. OpenSearch failures propagate
 * as runtime exceptions and are mapped to a 500 by {@code GlobalExceptionHandler} — the search
 * never returns a fake empty page to hide an outage.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "catalog.search.backend", havingValue = "opensearch")
public class OpenSearchCatalogSearchService implements CatalogSearchService {

    private final OpenSearchClient client;
    private final OpenSearchProperties properties;
    private final EmbeddingService embeddingService;
    private final boolean semanticEnabled;
    private final String mode; // keyword | semantic | hybrid

    /** Number of nearest neighbors to request from the knn clause. */
    private static final int KNN_K = 100;

    public OpenSearchCatalogSearchService(
            OpenSearchClient client,
            OpenSearchProperties properties,
            EmbeddingService embeddingService,
            @Value("${catalog.search.semantic-enabled:true}") boolean semanticEnabled,
            @Value("${catalog.search.mode:hybrid}") String mode) {
        this.client = client;
        this.properties = properties;
        this.embeddingService = embeddingService;
        this.semanticEnabled = semanticEnabled;
        this.mode = mode;
    }

    @Override
    public CatalogSearchResults search(CatalogSearchQuery query) {
        int pageSize = Math.max(1, query.pageSize());
        int page = Math.max(0, query.page());
        long from = (long) page * pageSize;

        Query osQuery = buildQuery(query);

        SearchRequest request = new SearchRequest.Builder()
                .index(properties.getIndex())
                .query(osQuery)
                .from((int) Math.min(from, Integer.MAX_VALUE))
                .size(pageSize)
                .build();

        try {
            SearchResponse<CatalogRecipeDto> response =
                    client.search(request, CatalogRecipeDto.class);

            List<CatalogRecipeDto> items = new ArrayList<>();
            response.hits().hits().forEach(hit -> {
                if (hit.source() != null) {
                    items.add(hit.source());
                }
            });

            long total = response.hits().total() != null
                    ? response.hits().total().value()
                    : items.size();

            return new CatalogSearchResults(items, page, pageSize, total);
        } catch (IOException e) {
            // Do NOT swallow into an empty page: let it surface as a 500 via GlobalExceptionHandler.
            throw new IllegalStateException("OpenSearch search failed", e);
        }
    }

    /**
     * Builds the OpenSearch query mirroring in-app semantics. Dietary tags are always a filter;
     * text drives keyword and/or knn clauses depending on mode + semantic-enabled.
     */
    private Query buildQuery(CatalogSearchQuery query) {
        BoolQuery.Builder bool = new BoolQuery.Builder();

        // Dietary filter (AND): one term filter per required tag.
        List<String> tags = query.dietaryTags();
        if (tags != null && !tags.isEmpty()) {
            for (String tag : tags) {
                bool.filter(f -> f.term(t -> t
                        .field("dietaryTags")
                        .value(FieldValue.of(tag))));
            }
        }

        boolean hasText = query.text() != null && !query.text().isBlank();
        if (!hasText) {
            // Browse: match_all + dietary filter.
            bool.must(m -> m.matchAll(ma -> ma));
            return Query.of(q -> q.bool(bool.build()));
        }

        String text = query.text();
        boolean keywordMode = "keyword".equalsIgnoreCase(mode);
        boolean semanticMode = "semantic".equalsIgnoreCase(mode);
        boolean useSemantic = semanticEnabled && !keywordMode;

        float[] queryVector = null;
        if (useSemantic) {
            queryVector = embedQuietly(text);
        }

        boolean addedTextClause = false;

        // Keyword clause (keyword or hybrid mode, or semantic fallback when the vector is null).
        if (!semanticMode || queryVector == null) {
            bool.should(keywordClause(text));
            addedTextClause = true;
        }

        // Vector clause (semantic or hybrid mode, when embedding succeeded).
        if (useSemantic && queryVector != null) {
            bool.should(knnClause(queryVector));
            addedTextClause = true;
        }

        // At least one text clause must match (so results are filtered, not just ranked).
        if (addedTextClause) {
            bool.minimumShouldMatch("1");
        }

        return Query.of(q -> q.bool(bool.build()));
    }

    private Query keywordClause(String text) {
        return Query.of(q -> q.multiMatch(mm -> mm
                .query(text)
                // Title boosted above body fields, mirroring the in-app 2x title weighting.
                .fields("title^3", "description", "ingredients")));
    }

    private Query knnClause(float[] vector) {
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) {
            vec.add(v);
        }
        return Query.of(q -> q.knn(k -> k
                .field("embedding")
                .vector(vec)
                .k(KNN_K)));
    }

    /** Embeds the query text, returning null (not throwing) so search can fall back to keyword. */
    private float[] embedQuietly(String text) {
        try {
            List<Double> embedding = embeddingService.embed(text);
            if (embedding == null || embedding.isEmpty()) {
                return null;
            }
            float[] arr = new float[embedding.size()];
            for (int i = 0; i < arr.length; i++) {
                arr[i] = embedding.get(i).floatValue();
            }
            return arr;
        } catch (Exception e) {
            log.warn("Query embedding failed, falling back to keyword search: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<CatalogRecipeDto> findById(String catalogRecipeId) {
        try {
            var response = client.get(g -> g
                    .index(properties.getIndex())
                    .id(catalogRecipeId), CatalogRecipeDto.class);
            if (response.found() && response.source() != null) {
                return Optional.of(response.source());
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch get failed for id " + catalogRecipeId, e);
        }
    }
}
