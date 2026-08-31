package io.asbun.backend.search;

import io.asbun.backend.config.OpenSearchProperties;
import io.asbun.backend.model.CatalogRecipe;
import io.asbun.backend.repository.CatalogRecipeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CatalogReindexRunner: bulk-indexes from DynamoDB using catalogRecipeId as the
 * document id (idempotent upsert), never calls Bedrock (embeddings read from the persisted
 * recipe), and skips items without an id.
 *
 * Feature: opensearch-catalog-backend
 * Validates: Task 6.2, 6.3 (Requirements 5.2, 5.3, 5.4)
 */
class CatalogReindexRunnerTest {

    private CatalogRecipe recipe(String id) {
        return CatalogRecipe.builder()
                .catalogRecipeId(id)
                .title("Recipe " + id)
                .embedding(List.of(0.1, 0.2, 0.3))
                .dietaryTags(List.of("VEGAN"))
                .build();
    }

    @SuppressWarnings("unchecked")
    private CatalogRecipeRepository repoReturning(List<CatalogRecipe> page) {
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.forTable(any())).thenReturn(repo);
        when(repo.tableName()).thenReturn("catalog-full");
        doAnswer(inv -> {
            Consumer<List<CatalogRecipe>> consumer = inv.getArgument(0);
            consumer.accept(page);
            return null;
        }).when(repo).scanInPages(any());
        return repo;
    }

    private OpenSearchClient clientNoErrors() throws Exception {
        OpenSearchClient client = mock(OpenSearchClient.class);
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(false);
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
        return client;
    }

    private OpenSearchProperties props() {
        OpenSearchProperties p = new OpenSearchProperties();
        p.setIndex("catalog-recipes");
        return p;
    }

    @Test
    void bulkIndexes_usingCatalogRecipeIdAsDocumentId() throws Exception {
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("aaa"), recipe("bbb")));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props(), 500, "catalog-full");

        runner.run();

        // Index is ensured before indexing.
        verify(provisioner).ensureIndex();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());

        List<BulkOperation> ops = captor.getValue().operations();
        assertThat(ops).hasSize(2);
        assertThat(ops).allSatisfy(op -> assertThat(op.isIndex()).isTrue());
        assertThat(ops.get(0).index().id()).isEqualTo("aaa");
        assertThat(ops.get(1).index().id()).isEqualTo("bbb");
    }

    @Test
    void skipsRecipesWithoutId() throws Exception {
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("aaa"), recipe(null)));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props(), 500, "catalog-full");

        runner.run();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        assertThat(captor.getValue().operations()).hasSize(1);
        assertThat(captor.getValue().operations().get(0).index().id()).isEqualTo("aaa");
    }

    @Test
    void batchesFlushWhenBatchSizeReached() throws Exception {
        CatalogRecipeRepository repo = repoReturning(
                List.of(recipe("a"), recipe("b"), recipe("c"), recipe("d"), recipe("e")));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        // batchSize 2 over 5 recipes => flushes of 2, 2, then remainder 1 = 3 bulk calls.
        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props(), 2, "catalog-full");

        runner.run();

        verify(client, org.mockito.Mockito.times(3)).bulk(any(BulkRequest.class));
    }
}
