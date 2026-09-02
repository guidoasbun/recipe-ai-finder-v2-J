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

    private OpenSearchProperties props(String signingService) {
        OpenSearchProperties p = new OpenSearchProperties();
        p.setIndex("catalog-recipes");
        p.setSigningService(signingService);
        return p;
    }

    @Test
    void managedDomain_usesCatalogRecipeIdAsDocumentId() throws Exception {
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("aaa"), recipe("bbb")));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        // Managed domain (es) can set a custom _id.
        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props("es"), 500, "catalog-full", false, 2, false, "", "", false);

        runner.run();

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
    void serverless_omitsDocumentId() throws Exception {
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("aaa")));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        // Serverless (aoss) rejects a custom _id, so it must be omitted (auto-generated).
        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props("aoss"), 500, "catalog-full", false, 2, false, "", "", false);

        runner.run();

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        List<BulkOperation> ops = captor.getValue().operations();
        assertThat(ops).hasSize(1);
        assertThat(ops.get(0).index().id()).isNull();
    }

    @Test
    void skipsRecipesWithoutId() throws Exception {
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("aaa"), recipe(null)));
        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props("es"), 500, "catalog-full", false, 2, false, "", "", false);

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
                repo, client, provisioner, props("es"), 2, "catalog-full", false, 2, false, "", "", false);

        runner.run();

        verify(client, org.mockito.Mockito.times(3)).bulk(any(BulkRequest.class));
    }

    @Test
    void writesFailedIdsToFileAndThrows(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        // One recipe whose bulk item comes back with an error => it must be recorded as failed.
        CatalogRecipeRepository repo = repoReturning(List.of(recipe("bad-1")));
        OpenSearchClient client = mock(OpenSearchClient.class);
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(true);
        var item = mock(org.opensearch.client.opensearch.core.bulk.BulkResponseItem.class);
        var err = mock(org.opensearch.client.opensearch._types.ErrorCause.class);
        when(err.reason()).thenReturn("rejected execution ... [throttled]");
        when(item.error()).thenReturn(err);
        when(response.items()).thenReturn(List.of(item));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        java.nio.file.Path failedFile = tmp.resolve("failed-ids.txt");
        // maxAttempts is fixed at 6 in full-reindex flush; batchSize 1, concurrency 1.
        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props("aoss"), 1, "catalog-full", false, 1,
                false, "", failedFile.toString(), false);

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, runner::run))
                .hasMessageContaining("failed item");

        assertThat(java.nio.file.Files.readAllLines(failedFile))
                .containsExactly("bad-1");
    }

    @Test
    void backfillFromIdsFile_indexesOnlyThoseRecipes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        // Backfill must NOT scan the table; it loads each id via findById and indexes only those.
        CatalogRecipeRepository repo = mock(CatalogRecipeRepository.class);
        when(repo.forTable(any())).thenReturn(repo);
        when(repo.tableName()).thenReturn("catalog-full");
        when(repo.findById("miss-1")).thenReturn(java.util.Optional.of(recipe("miss-1")));
        when(repo.findById("miss-2")).thenReturn(java.util.Optional.of(recipe("miss-2")));

        OpenSearchClient client = clientNoErrors();
        OpenSearchIndexProvisioner provisioner = mock(OpenSearchIndexProvisioner.class);

        java.nio.file.Path idsFile = tmp.resolve("ids.txt");
        java.nio.file.Files.write(idsFile, List.of("miss-1", "miss-2"));

        CatalogReindexRunner runner = new CatalogReindexRunner(
                repo, client, provisioner, props("aoss"), 500, "catalog-full", false, 4,
                true, idsFile.toString(), "", false);

        runner.run();

        // ensureIndex is called, but the index is never recreated during backfill.
        verify(provisioner).ensureIndex();
        verify(provisioner, never()).deleteIndexIfExists();
        // Never scans the whole table in backfill mode.
        verify(repo, never()).scanInPages(any());
        verify(repo).findById("miss-1");
        verify(repo).findById("miss-2");

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(captor.capture());
        assertThat(captor.getValue().operations()).hasSize(2);
    }
}
