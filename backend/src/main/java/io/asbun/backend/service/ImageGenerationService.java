package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.Base64;

@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String IMAGE_MODEL_ID = "amazon.titan-image-generator-v1";

    public String generateAndUploadImage(String recipeId, String recipeTitle) {
        try {
            String requestBody = buildImageRequest(recipeTitle);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(IMAGE_MODEL_ID)
                    .body(SdkBytes.fromUtf8String(requestBody))
                    .contentType("application/json")
                    .accept("application/json")
                    .build();

            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
            JsonNode root = objectMapper.readTree(response.body().asUtf8String());
            String base64Image = root.path("images").get(0).asText();
            byte[] imageBytes = Base64.getDecoder().decode(base64Image);

            return s3Service.uploadImage(recipeId, imageBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate image for: " + recipeTitle, e);
        }
    }

    private String buildImageRequest(String recipeTitle) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("taskType", "TEXT_IMAGE");

        ObjectNode textToImageParams = body.putObject("textToImageParams");
        textToImageParams.put("text",
            "A beautiful food photography photo of " + recipeTitle +
            ", professional lighting, high quality, restaurant style");

        ObjectNode imageConfig = body.putObject("imageGenerationConfig");
        imageConfig.put("numberOfImages", 1);
        imageConfig.put("width", 512);
        imageConfig.put("height", 512);
        imageConfig.put("cfgScale", 8.0);

        return objectMapper.writeValueAsString(body);
    }
}
