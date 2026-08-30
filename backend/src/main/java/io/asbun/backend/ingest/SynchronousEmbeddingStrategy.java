package io.asbun.backend.ingest;

import io.asbun.backend.service.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Synchronous, paced embedding strategy for bulk ingestion up to the in-app ceiling (~50K).
 * Paces requests below the account RPM quota; {@link EmbeddingService} handles backoff on
 * throttling. Resume/skip-if-already-embedded is enforced by the ingestion runner, which
 * only calls this for recipes that do not yet have an embedding.
 */
@Slf4j
@Component
public class SynchronousEmbeddingStrategy implements EmbeddingStrategy {

    private final EmbeddingService embeddingService;
    private final long minIntervalMillis;

    public SynchronousEmbeddingStrategy(EmbeddingService embeddingService,
                                        @Value("${catalog.ingest.rpm-limit:300}") int rpmLimit) {
        this.embeddingService = embeddingService;
        // Convert requests-per-minute into a minimum spacing between calls.
        this.minIntervalMillis = rpmLimit > 0 ? (60_000L / rpmLimit) : 0L;
    }

    @Override
    public List<Double> embed(String text) {
        List<Double> vector = embeddingService.embed(text);
        pace();
        return vector;
    }

    private void pace() {
        if (minIntervalMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(minIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
