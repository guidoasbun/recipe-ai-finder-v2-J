package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.asbun.backend.model.enums.ImageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

@Service
public class ImageGenerationService {

    private final RestTemplate restTemplate;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stability.api-key}")
    private String stabilityApiKey;

    @Value("${openai.api-key}")
    private String openaiApiKey;

    private static final String STABILITY_URL =
            "https://api.stability.ai/v2beta/stable-image/generate/core";
    private static final String OPENAI_URL =
            "https://api.openai.com/v1/images/generations";
    private static final String PROMPT_PREFIX =
            "A beautiful food photography photo of ";
    private static final String PROMPT_SUFFIX =
            ", professional lighting, high quality, restaurant style";

    public ImageGenerationService(RestTemplate restTemplate, S3Service s3Service) {
        this.restTemplate = restTemplate;
        this.s3Service = s3Service;
    }

    public String generateAndUploadImage(String recipeId, String recipeTitle, ImageModel imageModel) {
        try {
            byte[] imageBytes = switch (imageModel) {
                case STABILITY_CORE -> generateWithStability(recipeTitle);
                case GPT_IMAGE_1_5  -> generateWithGptImage(recipeTitle);
            };
            return s3Service.uploadImage(recipeId, imageBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate image for: " + recipeTitle, e);
        }
    }

    private byte[] generateWithStability(String recipeTitle) throws Exception {
        String prompt = PROMPT_PREFIX + recipeTitle + PROMPT_SUFFIX;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(stabilityApiKey);
        headers.set("Accept", "application/json");

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("prompt", prompt);
        body.add("output_format", "png");
        body.add("aspect_ratio", "1:1");

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(STABILITY_URL, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        String base64Image = root.path("image").asText();
        return Base64.getDecoder().decode(base64Image);
    }

    private byte[] generateWithGptImage(String recipeTitle) throws Exception {
        String prompt = PROMPT_PREFIX + recipeTitle + PROMPT_SUFFIX;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openaiApiKey);

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", "gpt-image-1.5");
        requestBody.put("prompt", prompt);
        requestBody.put("n", 1);
        requestBody.put("size", "1024x1024");

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        ResponseEntity<String> response = restTemplate.postForEntity(OPENAI_URL, entity, String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
        String base64Image = root.path("data").get(0).path("b64_json").asText();
        return Base64.getDecoder().decode(base64Image);
    }
}
