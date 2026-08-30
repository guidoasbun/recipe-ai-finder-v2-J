package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Produces embedding vectors via Amazon Bedrock Titan Text Embeddings V2.
 * Used for single-query embedding at search time; bulk ingestion uses an
 * {@code EmbeddingStrategy} that delegates here.
 *
 * <p>Titan embedding models are throttled by requests-per-minute (RPM), not tokens.
 */
@Slf4j
@Service
public class EmbeddingService {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String modelId;

    public EmbeddingService(BedrockRuntimeClient bedrockRuntimeClient,
                            @Value("${bedrock.embedding.model-id}") String modelId) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.modelId = modelId;
    }

    /**
     * Embeds a single text into a vector. Retries a few times on transient failures
     * (including throttling). Throws if all attempts fail; callers that must degrade
     * gracefully should catch and fall back.
     */
    public List<Double> embed(String text) {
        String requestBody = buildRequestBody(text);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromUtf8String(requestBody))
                .contentType("application/json")
                .accept("application/json")
                .build();

        int maxAttempts = 5;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
                return parseResponse(response.body().asUtf8String());
            } catch (Exception e) {
                lastException = e;
                log.warn("Embedding attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    // Exponential backoff: 500ms, 1s, 2s, 4s
                    long backoff = 500L * (1L << (attempt - 1));
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new RuntimeException("Failed to embed text after " + maxAttempts + " attempts", lastException);
    }

    private String buildRequestBody(String text) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("inputText", text);
            body.put("normalize", true);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build embedding request", e);
        }
    }

    private List<Double> parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode vector = root.path("embedding");
            List<Double> result = new ArrayList<>(vector.size());
            vector.forEach(v -> result.add(v.asDouble()));
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse embedding response", e);
        }
    }
}
