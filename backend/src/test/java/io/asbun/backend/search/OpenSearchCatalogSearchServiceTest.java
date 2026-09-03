package io.asbun.backend.search;

import io.asbun.backend.config.OpenSearchProperties;
import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.service.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.core.search.TotalHits;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OpenSearchCatalogSearchService: query translation (dietary filter, keyword
 * clause, knn clause, mode handling, pagination), response mapping, findById, and semantic
 * fallback. The OpenSearch client is mocked; SearchRequests are captured and their typed query
 * structure is asserted.
 *
 * Feature: opensearch-catalog-backend
 * Validates: Task 8.1, 8.2, 8.3 (Requirements 2.1-2.7, 1.2, 1.3)
 */
class OpenSearchCatalogSearchServiceTest {

    private OpenSearchProperties props() {
        OpenSearchProperties p = new OpenSearchProperties();
        p.setIndex("catalog-recipes");
        return p;
    }

    @SuppressWarnings("unchecked")
    private OpenSearchClient clientReturningHits(CatalogRecipeDto... hits) throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);

        List<Hit<CatalogRecipeDto>> hitList = java.util.Arrays.stream(hits)
                .map(dto -> new Hit.Builder<CatalogRecipeDto>()
                        .index("catalog-recipes")
                        .id(dto.getCatalogRecipeId())
                        .source(dto)
                        .build())
                .toList();

        HitsMetadata<CatalogRecipeDto> meta = new HitsMetadata.Builder<CatalogRecipeDto>()
                .hits(hitList)
                .total(new TotalHits.Builder().value(hits.length).relation(
                        org.opensearch.client.opensearch.core.search.TotalHitsRelation.Eq).build())
                .build();

        SearchResponse<CatalogRecipeDto> response = new SearchResponse.Builder<CatalogRecipeDto>()
                .took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(meta)
                .build();

        when(client.search(any(SearchRequest.class), eq(CatalogRecipeDto.class))).thenReturn(response);
        return client;
    }

    private OpenSearchCatalogSearchService service(OpenSearchClient client,
                                                   EmbeddingService embeddingService,
                                                   boolean semanticEnabled,
                                                   String mode) {
        return new OpenSearchCatalogSearchService(client, props(), embeddingService, semanticEnabled, mode);
    }

    private CatalogRecipeDto dto(String id) {
        return CatalogRecipeDto.builder().catalogRecipeId(id).title("Recipe " + id).build();
    }

    private BoolQuery capturedBool(OpenSearchClient client) throws Exception {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(CatalogRecipeDto.class));
        Query q = captor.getValue().query();
        assertThat(q.isBool()).isTrue();
        return q.bool();
    }

    // --- Pagination ---

    @Test
    void pagination_setsFromAndSize() throws Exception {
        OpenSearchClient client = clientReturningHits(dto("a"));
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        svc.search(new CatalogSearchQuery("pasta", List.of(), 2, 10));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(CatalogRecipeDto.class));
        assertThat(captor.getValue().from()).isEqualTo(20); // page 2 * size 10
        assertThat(captor.getValue().size()).isEqualTo(10);
    }

    @Test
    void pagination_clampsNegativePageAndZeroSize() throws Exception {
        OpenSearchClient client = clientReturningHits();
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        svc.search(new CatalogSearchQuery("pasta", List.of(), -5, 0));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(client).search(captor.capture(), eq(CatalogRecipeDto.class));
        assertThat(captor.getValue().from()).isEqualTo(0);
        assertThat(captor.getValue().size()).isEqualTo(1); // floored to at least 1
    }

    // --- Dietary filter ---

    @Test
    void dietaryTags_becomeTermFilters_oneEach() throws Exception {
        OpenSearchClient client = clientReturningHits();
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        svc.search(new CatalogSearchQuery("pasta", List.of("VEGAN", "GLUTEN_FREE"), 0, 20));

        BoolQuery bool = capturedBool(client);
        assertThat(bool.filter()).hasSize(2);
        assertThat(bool.filter()).allSatisfy(f -> assertThat(f.isTerm()).isTrue());
        assertThat(bool.filter()).anySatisfy(f ->
                assertThat(f.term().field()).isEqualTo("dietaryTags"));
    }

    // --- Keyword mode ---

    @Test
    void keywordMode_addsMultiMatch_noKnn() throws Exception {
        OpenSearchClient client = clientReturningHits();
        EmbeddingService embed = mock(EmbeddingService.class);
        var svc = service(client, embed, true, "keyword"); // semanticEnabled true but keyword mode

        svc.search(new CatalogSearchQuery("spicy noodles", List.of(), 0, 20));

        BoolQuery bool = capturedBool(client);
        assertThat(bool.should()).anySatisfy(s -> assertThat(s.isMultiMatch()).isTrue());
        assertThat(bool.should()).noneSatisfy(s -> assertThat(s.isKnn()).isTrue());
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");
        // Keyword mode must not embed.
        verify(embed, org.mockito.Mockito.never()).embed(any());
    }

    // --- Semantic mode ---

    @Test
    void semanticMode_addsKnn_noKeyword() throws Exception {
        OpenSearchClient client = clientReturningHits();
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenReturn(List.of(0.1, 0.2, 0.3));
        var svc = service(client, embed, true, "semantic");

        svc.search(new CatalogSearchQuery("comforting winter stew", List.of(), 0, 20));

        BoolQuery bool = capturedBool(client);
        assertThat(bool.should()).anySatisfy(s -> assertThat(s.isKnn()).isTrue());
        assertThat(bool.should()).noneSatisfy(s -> assertThat(s.isMultiMatch()).isTrue());
    }

    @Test
    void semanticMode_knnK_coversRequestedPage() throws Exception {
        OpenSearchClient client = clientReturningHits();
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenReturn(List.of(0.1, 0.2, 0.3));
        var svc = service(client, embed, true, "semantic");

        // page 3, size 20 => needs k >= 80 (from+size), not a fixed 100 that would exclude deep pages.
        svc.search(new CatalogSearchQuery("stew", List.of(), 3, 20));

        BoolQuery bool = capturedBool(client);
        int k = bool.should().stream().filter(Query::isKnn).findFirst().orElseThrow().knn().k();
        assertThat(k).isGreaterThanOrEqualTo(80);
    }

    // --- Hybrid mode ---

    @Test
    void hybridMode_addsBothKeywordAndKnn() throws Exception {
        OpenSearchClient client = clientReturningHits();
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenReturn(List.of(0.1, 0.2, 0.3));
        var svc = service(client, embed, true, "hybrid");

        svc.search(new CatalogSearchQuery("garlic bread", List.of(), 0, 20));

        BoolQuery bool = capturedBool(client);
        assertThat(bool.should()).anySatisfy(s -> assertThat(s.isMultiMatch()).isTrue());
        assertThat(bool.should()).anySatisfy(s -> assertThat(s.isKnn()).isTrue());
        assertThat(bool.minimumShouldMatch()).isEqualTo("1");
    }

    // --- Browse (blank text) ---

    @Test
    void blankText_isMatchAllBrowse() throws Exception {
        OpenSearchClient client = clientReturningHits();
        var svc = service(client, mock(EmbeddingService.class), true, "hybrid");

        svc.search(new CatalogSearchQuery("   ", List.of("VEGAN"), 0, 20));

        BoolQuery bool = capturedBool(client);
        assertThat(bool.must()).anySatisfy(m -> assertThat(m.isMatchAll()).isTrue());
        assertThat(bool.filter()).hasSize(1); // dietary filter still applied
        assertThat(bool.should()).isEmpty();
    }

    // --- Semantic fallback ---

    @Test
    void semanticFailure_fallsBackToKeyword() throws Exception {
        OpenSearchClient client = clientReturningHits();
        EmbeddingService embed = mock(EmbeddingService.class);
        when(embed.embed(any())).thenThrow(new RuntimeException("bedrock down"));
        var svc = service(client, embed, true, "hybrid");

        svc.search(new CatalogSearchQuery("stir fry", List.of(), 0, 20));

        BoolQuery bool = capturedBool(client);
        // Vector clause dropped, keyword clause still present.
        assertThat(bool.should()).anySatisfy(s -> assertThat(s.isMultiMatch()).isTrue());
        assertThat(bool.should()).noneSatisfy(s -> assertThat(s.isKnn()).isTrue());
    }

    // --- Response mapping ---

    @Test
    void search_mapsHitsAndTotal() throws Exception {
        OpenSearchClient client = clientReturningHits(dto("a"), dto("b"));
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        CatalogSearchResults results = svc.search(new CatalogSearchQuery("x", List.of(), 0, 20));

        assertThat(results.items()).extracting(CatalogRecipeDto::getCatalogRecipeId)
                .containsExactly("a", "b");
        assertThat(results.totalMatches()).isEqualTo(2);
        assertThat(results.page()).isEqualTo(0);
        assertThat(results.pageSize()).isEqualTo(20);
    }

    // --- findById ---

    // findById queries by the catalogRecipeId field via search (serverless auto-generates _id,
    // so get-by-_id is not usable). Mock the Function-based search overload.

    @SuppressWarnings("unchecked")
    private OpenSearchClient clientFindByIdReturning(CatalogRecipeDto... hits) throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        List<Hit<CatalogRecipeDto>> hitList = java.util.Arrays.stream(hits)
                .map(dto -> new Hit.Builder<CatalogRecipeDto>()
                        .index("catalog-recipes").id(dto.getCatalogRecipeId()).source(dto).build())
                .toList();
        HitsMetadata<CatalogRecipeDto> meta = new HitsMetadata.Builder<CatalogRecipeDto>()
                .hits(hitList)
                .total(new TotalHits.Builder().value(hits.length)
                        .relation(org.opensearch.client.opensearch.core.search.TotalHitsRelation.Eq).build())
                .build();
        SearchResponse<CatalogRecipeDto> response = new SearchResponse.Builder<CatalogRecipeDto>()
                .took(1).timedOut(false).shards(s -> s.total(1).successful(1).failed(0)).hits(meta).build();
        when(client.search(any(Function.class), eq(CatalogRecipeDto.class))).thenReturn(response);
        return client;
    }

    @Test
    void findById_returnsDtoWhenFound() throws Exception {
        OpenSearchClient client = clientFindByIdReturning(dto("abc"));
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        Optional<CatalogRecipeDto> found = svc.findById("abc");

        assertThat(found).isPresent();
        assertThat(found.get().getCatalogRecipeId()).isEqualTo("abc");
    }

    @Test
    void findById_emptyWhenNotFound() throws Exception {
        OpenSearchClient client = clientFindByIdReturning(); // no hits
        var svc = service(client, mock(EmbeddingService.class), false, "keyword");

        assertThat(svc.findById("missing")).isEmpty();
    }
}
