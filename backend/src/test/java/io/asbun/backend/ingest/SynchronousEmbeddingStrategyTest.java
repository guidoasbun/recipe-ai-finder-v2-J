package io.asbun.backend.ingest;

import io.asbun.backend.service.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SynchronousEmbeddingStrategy: delegates to EmbeddingService and paces calls.
 *
 * Feature: existing-recipe-search
 * Validates: Requirements 3.6
 */
class SynchronousEmbeddingStrategyTest {

    @Test
    void embed_delegatesToEmbeddingService() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(eq("pasta"))).thenReturn(List.of(0.5, 0.5));

        // rpm 0 => no pacing delay, keeps the test fast
        SynchronousEmbeddingStrategy strategy = new SynchronousEmbeddingStrategy(embeddingService, 0);
        List<Double> vector = strategy.embed("pasta");

        assertThat(vector).containsExactly(0.5, 0.5);
        verify(embeddingService).embed("pasta");
    }

    @Test
    void embed_pacesAccordingToRpmLimit() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.embed(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(1.0));

        // 600 rpm => 100ms minimum spacing between calls.
        SynchronousEmbeddingStrategy strategy = new SynchronousEmbeddingStrategy(embeddingService, 600);

        long start = System.currentTimeMillis();
        strategy.embed("a");
        long elapsed = System.currentTimeMillis() - start;

        // Each call sleeps ~100ms after embedding; allow slack for CI timing.
        assertThat(elapsed).isGreaterThanOrEqualTo(80L);
    }
}
