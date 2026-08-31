package io.asbun.backend.ingest;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.bedrock.BedrockClient;
import software.amazon.awssdk.services.bedrock.model.CreateModelInvocationJobRequest;
import software.amazon.awssdk.services.bedrock.model.CreateModelInvocationJobResponse;
import software.amazon.awssdk.services.bedrock.model.GetModelInvocationJobRequest;
import software.amazon.awssdk.services.bedrock.model.GetModelInvocationJobResponse;
import software.amazon.awssdk.services.bedrock.model.ModelInvocationJobStatus;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BatchEmbeddingStrategy (Bedrock Batch Inference). Mocks S3 + the Bedrock
 * control-plane client to verify: JSONL input upload, job submit, poll-to-completion, and
 * output parsing back into vectors keyed by recordId.
 *
 * Feature: opensearch-catalog-backend
 * Validates: Task 10.2
 */
class BatchEmbeddingStrategyTest {

    private BatchEmbeddingStrategy strategy(S3Client s3, BedrockClient bedrock) {
        return new BatchEmbeddingStrategy(
                s3, bedrock, "amazon.titan-embed-text-v2:0",
                "in-bucket", "out-bucket", "arn:aws:iam::123:role/batch", 0);
    }

    private ResponseInputStream<GetObjectResponse> s3Stream(String body) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void embedAll_uploadsJsonl_submits_polls_andParsesOutput() {
        S3Client s3 = mock(S3Client.class);
        BedrockClient bedrock = mock(BedrockClient.class);

        // Submit returns a job ARN.
        when(bedrock.createModelInvocationJob(any(CreateModelInvocationJobRequest.class)))
                .thenReturn(CreateModelInvocationJobResponse.builder().jobArn("job-arn-1").build());

        // First poll InProgress, then Completed.
        when(bedrock.getModelInvocationJob(any(GetModelInvocationJobRequest.class)))
                .thenReturn(GetModelInvocationJobResponse.builder().status(ModelInvocationJobStatus.IN_PROGRESS).build())
                .thenReturn(GetModelInvocationJobResponse.builder().status(ModelInvocationJobStatus.COMPLETED).build());

        // Output listing: one result file.
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("batch-embed/x/out/part.jsonl.out").build())
                        .isTruncated(false)
                        .build());

        // Output content: two records, one good, one with an error object.
        String out = "{\"recordId\":\"a\",\"modelOutput\":{\"embedding\":[0.1,0.2,0.3]}}\n"
                + "{\"recordId\":\"b\",\"error\":{\"message\":\"bad\"}}\n";
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(s3Stream(out));

        java.util.LinkedHashMap<String, List<Double>> result = new java.util.LinkedHashMap<>();
        long emitted = strategy(s3, bedrock)
                .embedAll(new java.util.LinkedHashMap<>(Map.of("a", "apple pie", "b", "burnt toast")),
                        result::put);

        // 'a' embedded, 'b' had an error → omitted.
        assertThat(emitted).isEqualTo(1);
        assertThat(result).containsOnlyKeys("a");
        assertThat(result.get("a")).containsExactly(0.1, 0.2, 0.3);

        // Uploaded JSONL to the input bucket.
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("in-bucket");
        assertThat(put.getValue().key()).startsWith("batch-embed/");

        // Submitted a job with the model + role.
        ArgumentCaptor<CreateModelInvocationJobRequest> job =
                ArgumentCaptor.forClass(CreateModelInvocationJobRequest.class);
        verify(bedrock).createModelInvocationJob(job.capture());
        assertThat(job.getValue().modelId()).isEqualTo("amazon.titan-embed-text-v2:0");
        assertThat(job.getValue().roleArn()).isEqualTo("arn:aws:iam::123:role/batch");
    }

    @Test
    void embedAll_failsWhenJobFails() {
        S3Client s3 = mock(S3Client.class);
        BedrockClient bedrock = mock(BedrockClient.class);
        when(bedrock.createModelInvocationJob(any(CreateModelInvocationJobRequest.class)))
                .thenReturn(CreateModelInvocationJobResponse.builder().jobArn("job-arn-1").build());
        when(bedrock.getModelInvocationJob(any(GetModelInvocationJobRequest.class)))
                .thenReturn(GetModelInvocationJobResponse.builder()
                        .status(ModelInvocationJobStatus.FAILED).message("boom").build());

        assertThatThrownBy(() -> strategy(s3, bedrock).embedAll(Map.of("a", "x"), (id, v) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not complete");
    }

    @Test
    void embedAll_requiresBucketsAndRole() {
        BatchEmbeddingStrategy missingConfig = new BatchEmbeddingStrategy(
                mock(S3Client.class), mock(BedrockClient.class),
                "amazon.titan-embed-text-v2:0", "", "", "", 0);

        assertThatThrownBy(() -> missingConfig.embedAll(Map.of("a", "x"), (id, v) -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("input-bucket");
    }

    @Test
    void embed_singleText_isUnsupported() {
        BatchEmbeddingStrategy s = strategy(mock(S3Client.class), mock(BedrockClient.class));
        assertThatThrownBy(() -> s.embed("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void embedAll_splitsIntoMultipleJobsAboveRecordLimit() throws Exception {
        S3Client s3 = mock(S3Client.class);
        BedrockClient bedrock = mock(BedrockClient.class);
        when(bedrock.createModelInvocationJob(any(CreateModelInvocationJobRequest.class)))
                .thenReturn(CreateModelInvocationJobResponse.builder().jobArn("job-arn").build());
        when(bedrock.getModelInvocationJob(any(GetModelInvocationJobRequest.class)))
                .thenReturn(GetModelInvocationJobResponse.builder().status(ModelInvocationJobStatus.COMPLETED).build());
        // Each output listing empty (we only assert job count / splitting here).
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());

        // 100,001 records => must split into 2 jobs (100K cap).
        java.util.LinkedHashMap<String, String> inputs = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100_001; i++) {
            inputs.put("id" + i, "t");
        }

        strategy(s3, bedrock).embedAll(inputs, (id, v) -> {});

        // 2 jobs submitted, 2 input files uploaded.
        verify(bedrock, org.mockito.Mockito.times(2)).createModelInvocationJob(any(CreateModelInvocationJobRequest.class));
        verify(s3, org.mockito.Mockito.times(2)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
