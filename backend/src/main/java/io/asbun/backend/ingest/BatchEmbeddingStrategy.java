package io.asbun.backend.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.CreateModelInvocationJobRequest;
import software.amazon.awssdk.services.bedrock.model.GetModelInvocationJobRequest;
import software.amazon.awssdk.services.bedrock.model.GetModelInvocationJobResponse;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobInputDataConfig;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobOutputDataConfig;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobS3InputDataConfig;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobS3OutputDataConfig;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobStatus;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full-dataset embedding via Amazon Bedrock <b>Batch Inference</b>, for the ~2.2M RecipeNLG
 * load where the synchronous per-request loop ({@link SynchronousEmbeddingStrategy}) would be
 * RPM-bound and take many hours. Batch inference processes a whole input file asynchronously at
 * ~50% of on-demand cost.
 *
 * <p>Lifecycle ({@link #embedAll}): write inputs as JSONL to S3 → submit a
 * {@code CreateModelInvocationJob} → poll to completion → read the S3 output JSONL and map each
 * {@code recordId} back to its embedding vector.
 *
 * <p>The per-text {@link #embed(String)} contract is intentionally unsupported here: batch
 * inference is whole-file/async, the wrong granularity for a single call. Ingestion uses
 * {@link #embedAll} on the batch path; the synchronous strategy remains for catalogs ≤ ~50K.
 *
 * <p>Only active when {@code catalog.ingest.embedding-strategy=batch}.
 */
@Slf4j
@Component
public class BatchEmbeddingStrategy implements EmbeddingStrategy {

    // Bedrock Titan V2 batch quotas (us-east-1): max 100,000 records/job and 1 GB input file.
    // A single job must respect BOTH; embedAll splits its input into sub-jobs accordingly.
    private static final int MAX_RECORDS_PER_JOB = 100_000;
    private static final long MAX_INPUT_BYTES = 950L * 1024 * 1024; // ~950 MB safety margin under 1 GB

    private final S3Client s3Client;
    private final BedrockClient bedrockClient;
    private final String modelId;
    private final String inputBucket;
    private final String outputBucket;
    private final String roleArn;
    private final long pollSeconds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BatchEmbeddingStrategy(S3Client s3Client,
                                  BedrockClient bedrockClient,
                                  @Value("${bedrock.embedding.model-id}") String modelId,
                                  @Value("${bedrock.batch.input-bucket:}") String inputBucket,
                                  @Value("${bedrock.batch.output-bucket:}") String outputBucket,
                                  @Value("${bedrock.batch.role-arn:}") String roleArn,
                                  @Value("${bedrock.batch.poll-seconds:60}") long pollSeconds) {
        this.s3Client = s3Client;
        this.bedrockClient = bedrockClient;
        this.modelId = modelId;
        this.inputBucket = inputBucket;
        this.outputBucket = outputBucket;
        this.roleArn = roleArn;
        this.pollSeconds = pollSeconds;
    }

    @Override
    public List<Double> embed(String text) {
        throw new UnsupportedOperationException(
                "BatchEmbeddingStrategy is whole-file/async; use embedAll(...) for the batch path. "
                        + "For single-text/query embedding use EmbeddingService, and for catalogs "
                        + "<= ~50K use SynchronousEmbeddingStrategy.");
    }

    /**
     * Embeds all inputs via one or more Bedrock batch jobs. Splits into sub-jobs that each respect
     * the Bedrock limits ({@value #MAX_RECORDS_PER_JOB} records and ~1 GB input file), submitting,
     * polling, and collecting each in turn. Keys of the returned map are the same recordIds passed
     * in; a recordId is omitted if the model returned an error for it.
     *
     * @param inputs recordId (deterministic catalogRecipeId) -> text to embed
     * @return recordId -> embedding vector
     */
    public Map<String, List<Double>> embedAll(Map<String, String> inputs) {
        requireConfig();
        Map<String, List<Double>> allVectors = new LinkedHashMap<>();

        List<Map<String, String>> jobs = splitIntoJobs(inputs);
        log.info("Batch embedding {} records across {} job(s)", inputs.size(), jobs.size());

        int jobNum = 0;
        for (Map<String, String> jobInputs : jobs) {
            jobNum++;
            String jobStamp = Instant.now().toEpochMilli() + "-" + jobNum;
            String inputKey = "batch-embed/" + jobStamp + "/input.jsonl";
            String outputPrefix = "batch-embed/" + jobStamp + "/out/";

            uploadInputJsonl(jobInputs, inputKey);
            String jobArn = submitJob(jobStamp, inputKey, outputPrefix);
            log.info("Submitted batch job {}/{} ({} records): {}", jobNum, jobs.size(), jobInputs.size(), jobArn);

            waitForCompletion(jobArn);
            Map<String, List<Double>> vectors = collectOutput(outputPrefix);
            allVectors.putAll(vectors);
            log.info("Job {}/{} produced {} vectors", jobNum, jobs.size(), vectors.size());
        }

        log.info("Batch embedding produced {} vectors for {} inputs", allVectors.size(), inputs.size());
        return allVectors;
    }

    /**
     * Splits inputs into sub-jobs each within the record-count and input-file-size limits. The
     * per-record JSONL size is estimated as the record text plus a small fixed overhead for the
     * JSON envelope.
     */
    private List<Map<String, String>> splitIntoJobs(Map<String, String> inputs) {
        List<Map<String, String>> jobs = new ArrayList<>();
        Map<String, String> current = new LinkedHashMap<>();
        long currentBytes = 0;

        for (Map.Entry<String, String> e : inputs.entrySet()) {
            // Envelope: {"recordId":"...","modelInput":{"inputText":"...","normalize":true}}\n
            long recordBytes = estimateRecordBytes(e.getKey(), e.getValue());
            boolean wouldExceed = current.size() >= MAX_RECORDS_PER_JOB
                    || (currentBytes + recordBytes) > MAX_INPUT_BYTES;
            if (wouldExceed && !current.isEmpty()) {
                jobs.add(current);
                current = new LinkedHashMap<>();
                currentBytes = 0;
            }
            current.put(e.getKey(), e.getValue());
            currentBytes += recordBytes;
        }
        if (!current.isEmpty()) {
            jobs.add(current);
        }
        return jobs;
    }

    private long estimateRecordBytes(String recordId, String text) {
        // ~60 bytes of JSON envelope + UTF-8 length of id and text (UTF-8 worst case ~ chars).
        return 60L + recordId.length()
                + text.getBytes(StandardCharsets.UTF_8).length;
    }

    private void requireConfig() {
        if (inputBucket.isBlank() || outputBucket.isBlank() || roleArn.isBlank()) {
            throw new IllegalStateException(
                    "Batch embedding requires bedrock.batch.input-bucket, output-bucket, and role-arn.");
        }
    }

    private void uploadInputJsonl(Map<String, String> inputs, String key) {
        // Stream JSONL to a temp file and upload from disk rather than holding the whole payload
        // (and a second String/byte copy) in the heap — matters at chunk sizes in the tens of
        // thousands of records.
        java.nio.file.Path tmp = null;
        try {
            tmp = java.nio.file.Files.createTempFile("batch-embed-", ".jsonl");
            try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> e : inputs.entrySet()) {
                    ObjectNode modelInput = objectMapper.createObjectNode();
                    modelInput.put("inputText", e.getValue());
                    modelInput.put("normalize", true);

                    ObjectNode record = objectMapper.createObjectNode();
                    record.put("recordId", e.getKey());
                    record.set("modelInput", modelInput);

                    w.write(objectMapper.writeValueAsString(record));
                    w.write('\n');
                }
            }
            s3Client.putObject(
                    PutObjectRequest.builder().bucket(inputBucket).key(key).contentType("application/jsonl").build(),
                    RequestBody.fromFile(tmp));
            log.info("Uploaded {} batch input records to s3://{}/{}", inputs.size(), inputBucket, key);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to build/upload batch input", ex);
        } finally {
            if (tmp != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tmp);
                } catch (Exception ignore) {
                    // best-effort temp cleanup
                }
            }
        }
    }

    private String submitJob(String jobStamp, String inputKey, String outputPrefix) {
        CreateModelInvocationJobRequest request = CreateModelInvocationJobRequest.builder()
                .jobName("catalog-embed-" + jobStamp)
                .modelId(modelId)
                .roleArn(roleArn)
                .inputDataConfig(ModelInvocationJobInputDataConfig.builder()
                        .s3InputDataConfig(ModelInvocationJobS3InputDataConfig.builder()
                                .s3Uri("s3://" + inputBucket + "/" + inputKey)
                                .build())
                        .build())
                .outputDataConfig(ModelInvocationJobOutputDataConfig.builder()
                        .s3OutputDataConfig(ModelInvocationJobS3OutputDataConfig.builder()
                                .s3Uri("s3://" + outputBucket + "/" + outputPrefix)
                                .build())
                        .build())
                .build();
        return bedrockClient.createModelInvocationJob(request).jobArn();
    }

    private void waitForCompletion(String jobArn) {
        while (true) {
            GetModelInvocationJobResponse job = bedrockClient.getModelInvocationJob(
                    GetModelInvocationJobRequest.builder().jobIdentifier(jobArn).build());
            ModelInvocationJobStatus status = job.status();
            log.info("Batch job status: {}", status);
            if (status == ModelInvocationJobStatus.COMPLETED) {
                return;
            }
            if (status == ModelInvocationJobStatus.FAILED
                    || status == ModelInvocationJobStatus.STOPPED
                    || status == ModelInvocationJobStatus.EXPIRED) {
                throw new IllegalStateException("Batch job did not complete: " + status
                        + (job.message() != null ? " (" + job.message() + ")" : ""));
            }
            sleep();
        }
    }

    private Map<String, List<Double>> collectOutput(String outputPrefix) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response listing = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(outputBucket)
                    .prefix(outputPrefix)
                    .continuationToken(continuationToken)
                    .build());
            for (S3Object obj : listing.contents()) {
                if (obj.key().endsWith(".jsonl.out") || obj.key().endsWith(".jsonl")) {
                    readOutputFile(obj.key(), result);
                }
            }
            continuationToken = Boolean.TRUE.equals(listing.isTruncated()) ? listing.nextContinuationToken() : null;
        } while (continuationToken != null);
        return result;
    }

    private void readOutputFile(String key, Map<String, List<Double>> result) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(outputBucket).key(key).build();
        try (ResponseInputStream<?> in = s3Client.getObject(get);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(line);
                String recordId = root.path("recordId").asText(null);
                if (recordId == null) {
                    continue;
                }
                JsonNode embedding = root.path("modelOutput").path("embedding");
                if (embedding.isArray() && embedding.size() > 0) {
                    List<Double> vector = new ArrayList<>(embedding.size());
                    embedding.forEach(v -> vector.add(v.asDouble()));
                    result.put(recordId, vector);
                } else {
                    log.warn("No embedding for recordId {} (error or empty output)", recordId);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to read batch output " + key, e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(pollSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while polling batch job", e);
        }
    }
}
