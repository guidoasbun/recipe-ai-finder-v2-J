package io.asbun.backend.ingest;

import java.util.List;

/**
 * Strategy for producing embeddings during bulk catalog ingestion.
 *
 * <p>Phase 1 uses {@link SynchronousEmbeddingStrategy} (paced, RPM-limited, serves up to
 * ~50K recipes). A future {@code BatchEmbeddingStrategy} (Bedrock Batch Inference, S3 JSONL)
 * would implement this same interface for the ~2.2M dataset without changing the pipeline.
 */
public interface EmbeddingStrategy {

    /** Embeds a single text. Implementations handle pacing/backoff as appropriate. */
    List<Double> embed(String text);
}
