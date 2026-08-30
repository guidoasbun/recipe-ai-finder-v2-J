package io.asbun.backend.ingest;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * FUTURE (Phase 2, ~2.2M recipes): embed via Amazon Bedrock <b>Batch Inference</b> instead
 * of the synchronous per-request loop.
 *
 * <p>Rationale: at 2.2M recipes the synchronous {@link SynchronousEmbeddingStrategy} is
 * RPM-bound and would take many hours. Batch inference processes a whole input file
 * asynchronously at ~50% of on-demand cost and without per-request rate-limit babysitting.
 *
 * <p>Intended flow (NOT implemented here — deliberately a scaffold):
 * <ol>
 *   <li>Write all inputs as JSONL to S3, one record per recipe:
 *       {@code {"recordId":"<catalogRecipeId>","modelInput":{"inputText":"<text>"}}}.</li>
 *   <li>Submit a Bedrock {@code CreateModelInvocationJob} pointing at the S3 input/output.</li>
 *   <li>Poll the job until complete.</li>
 *   <li>Read the S3 output JSONL and map each {@code recordId} back to its embedding vector.</li>
 * </ol>
 *
 * <p>This class is intentionally NOT a Spring {@code @Component}, so it is never selected as
 * the active ingestion strategy in Phase 1. Wire it (and an S3 client + job config) when the
 * full dataset is adopted. See design.md §5.1 and §12.
 */
@Slf4j
public class BatchEmbeddingStrategy implements EmbeddingStrategy {

    /**
     * The synchronous {@link EmbeddingStrategy} contract embeds one text at a time, which is
     * the wrong granularity for batch inference (whole-file, async). A real batch
     * implementation would expose a file-oriented API (submit → poll → collect) rather than
     * per-text {@code embed}. This method is left unimplemented on purpose.
     */
    @Override
    public List<Double> embed(String text) {
        throw new UnsupportedOperationException(
                "BatchEmbeddingStrategy is a Phase 2 scaffold for Bedrock Batch Inference. "
                        + "Use SynchronousEmbeddingStrategy for catalogs up to ~50K. "
                        + "See design.md §5.1.");
    }
}
