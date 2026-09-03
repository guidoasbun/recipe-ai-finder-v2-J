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

    /** Upper bound on knn {@code k} so deep pagination is reachable without unbounded fan-out. */
    private static final int MAX_KNN_K = 1000;

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

        boolean hasText = query.text() != null && !query.text().isBlank();
        Query osQuery = buildQuery(query, hasText, (int) Math.min(from + pageSize, Integer.MAX_VALUE));

        SearchRequest.Builder builder = new SearchRequest.Builder()
                .index(properties.getIndex())
                .query(osQuery)
                .from((int) Math.min(from, Integer.MAX_VALUE))
                .size(pageSize)
                // Exact total-hit tracking: without this OpenSearch caps hits.total at 10,000,
                // which would understate totalMatches on the large catalog.
                .trackTotalHits(t -> t.enabled(true));

        // Deterministic tie-breaker so pagination is stable across shards/replicas. For text
        // queries this sorts by relevance first, then id; for browse it sorts purely by id.
        if (hasText) {
            builder.sort(s -> s.score(sc -> sc.order(org.opensearch.client.opensearch._types.SortOrder.Desc)));
        }
        builder.sort(s -> s.field(f -> f
                .field("catalogRecipeId")
                .order(org.opensearch.client.opensearch._types.SortOrder.Asc)));

        SearchRequest request = builder.build();

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
     *
     * @param candidateDepth number of neighbors the knn clause must cover (from + pageSize) so
     *                       deep pages are reachable; bounded by {@link #MAX_KNN_K}.
     */
    private Query buildQuery(CatalogSearchQuery query, boolean hasText, int candidateDepth) {
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
            bool.should(knnClause(queryVector, candidateDepth));
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

    private Query knnClause(float[] vector, int candidateDepth) {
        List<Float> vec = new ArrayList<>(vector.length);
        for (float v : vector) {
            vec.add(v);
        }
        // k must cover the requested page (from + pageSize), bounded by MAX_KNN_K, so a deep page
        // is reachable rather than capped at a fixed 100.
        //
        // NOTE: OpenSearch Serverless requires exactly ONE of k / distance / score on a knn query
        // and rejects min_score/max_distance ("[knn] requires exactly one of k, distance or score
        // to be set"). So the in-app 0.35 cosine threshold cannot be applied as a knn radial
        // filter here; we use k-bounded nearest neighbors. In hybrid mode the keyword clause is
        // the precision signal; in pure semantic mode results are the top-k by similarity.
        int k = Math.max(1, Math.min(candidateDepth, MAX_KNN_K));
        return Query.of(q -> q.knn(kn -> kn
                .field("embedding")
                .vector(vec)
                .k(k)));
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
        // Query by the catalogRecipeId field rather than the document _id: OpenSearch Serverless
        // auto-generates _id (custom ids are rejected at index time), so a get-by-_id would not
        // work. A term query on the stored field works for both serverless and managed domains.
        try {
            SearchResponse<CatalogRecipeDto> response = client.search(s -> s
                    .index(properties.getIndex())
                    .size(1)
                    .query(q -> q.term(t -> t
                            .field("catalogRecipeId")
                            .value(FieldValue.of(catalogRecipeId)))),
                    CatalogRecipeDto.class);
            return response.hits().hits().stream()
                    .map(org.opensearch.client.opensearch.core.search.Hit::source)
                    .filter(java.util.Objects::nonNull)
                    .findFirst();
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch findById failed for id " + catalogRecipeId, e);
        }
    }
}
