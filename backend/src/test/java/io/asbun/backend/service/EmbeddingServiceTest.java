package io.asbun.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EmbeddingService: request body shape and response parsing.
 *
 * Feature: existing-recipe-search
 * Validates: Requirements 3.1, 3.2
 */
class EmbeddingServiceTest {

    private static final String MODEL_ID = "amazon.titan-embed-text-v2:0";
    private final ObjectMapper mapper = new ObjectMapper();

    private InvokeModelResponse responseWith(String json) {
        return InvokeModelResponse.builder()
                .body(SdkBytes.fromUtf8String(json))
                .build();
    }

    @Test
    void embed_parsesEmbeddingArray() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.invokeModel(any(InvokeModelRequest.class)))
                .thenReturn(responseWith("{\"embedding\":[0.1,0.2,0.3]}"));

        EmbeddingService service = new EmbeddingService(client, MODEL_ID);
        List<Double> vector = service.embed("chicken and rice");

        assertThat(vector).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void embed_buildsInputTextRequestWithModelId() throws Exception {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        var captor = org.mockito.ArgumentCaptor.forClass(InvokeModelRequest.class);
        when(client.invokeModel(captor.capture()))
                .thenReturn(responseWith("{\"embedding\":[1.0]}"));

        EmbeddingService service = new EmbeddingService(client, MODEL_ID);
        service.embed("tomato soup");

        InvokeModelRequest req = captor.getValue();
        assertThat(req.modelId()).isEqualTo(MODEL_ID);
        assertThat(req.contentType()).isEqualTo("application/json");

        var body = mapper.readTree(req.body().asUtf8String());
        assertThat(body.path("inputText").asText()).isEqualTo("tomato soup");
    }

    @Test
    void embed_retriesThenFailsAfterExhaustingAttempts() {
        BedrockRuntimeClient client = mock(BedrockRuntimeClient.class);
        when(client.invokeModel(any(InvokeModelRequest.class)))
                .thenThrow(new RuntimeException("throttled"));

        EmbeddingService service = new EmbeddingService(client, MODEL_ID);

        assertThatThrownBy(() -> service.embed("x"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to embed");
    }
}
