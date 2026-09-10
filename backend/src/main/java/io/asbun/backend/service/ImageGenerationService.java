package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.asbun.backend.dto.ImageUploadResult;
import io.asbun.backend.model.enums.ImageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    private final RestTemplate restTemplate;
    private final S3Service s3Service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stability.api-key}")
    private String stabilityApiKey;

    @Value("${openai.api-key}")
    private String openaiApiKey;

    @Value("${google.api-key}")
    private String googleApiKey;

    private static final String STABILITY_URL =
            "https://api.stability.ai/v2beta/stable-image/generate/core";
    private static final String OPENAI_URL =
            "https://api.openai.com/v1/images/generations";
    // Nano Banana (Gemini native image generation) via the Interactions API. The old Imagen
    // models (imagen-4.0-*) reached their shutdown date and now return 404, so the Google image
    // path was migrated to gemini-3.1-flash-image / -lite-image on this endpoint.
    // See https://ai.google.dev/gemini-api/docs/image-generation
    private static final String GOOGLE_INTERACTIONS_URL =
            "https://generativelanguage.googleapis.com/v1beta/interactions";
    private static final String PROMPT_PREFIX =
            "A beautiful food photography photo of ";
    private static final String PROMPT_SUFFIX =
            ", professional lighting, high quality, restaurant style";

    public ImageGenerationService(RestTemplate restTemplate, S3Service s3Service) {
        this.restTemplate = restTemplate;
        this.s3Service = s3Service;
    }

    public ImageUploadResult generateAndUploadImage(String recipeId, String recipeTitle, ImageModel imageModel) {
        try {
            long start = System.currentTimeMillis();
            byte[] imageBytes = switch (imageModel) {
                case STABILITY_CORE      -> generateWithStability(recipeTitle);
                case GPT_IMAGE_1_5       -> generateWithGptImage(recipeTitle);
                // Enum names retained for DynamoDB backward-compat; they now map to Nano Banana.
                case GOOGLE_IMAGEN_4      -> generateWithGoogleImagen(recipeTitle, "gemini-3.1-flash-image");
                case GOOGLE_IMAGEN_4_FAST -> generateWithGoogleImagen(recipeTitle, "gemini-3.1-flash-lite-image");
            };
            long generationMs = System.currentTimeMillis() - start;

            int width = 0, height = 0;
            try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                BufferedImage img = ImageIO.read(bais);
                if (img != null) {
                    width  = img.getWidth();
                    height = img.getHeight();
                }
            } catch (Exception e) {
                log.warn("Could not read image dimensions for recipe {}", recipeId, e);
            }

            // Providers don't all return PNG (e.g. Nano Banana returns JPEG), so detect the real
            // format from the bytes and store the correct Content-Type instead of assuming PNG.
            String contentType = detectContentType(imageBytes);
            String s3Key = s3Service.uploadImage(recipeId, imageBytes, contentType);
            return new ImageUploadResult(
                    s3Key,
                    width  > 0 ? width  : null,
                    height > 0 ? height : null,
                    contentType,
                    (long) imageBytes.length,
                    generationMs
            );
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

    private byte[] generateWithGoogleImagen(String recipeTitle, String modelId) throws Exception {
        String prompt = PROMPT_PREFIX + recipeTitle + PROMPT_SUFFIX;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", googleApiKey);

        // Interactions API request: input is an array of typed content parts; response_format asks
        // for an image at a fixed aspect ratio so the result stays consistent with the other
        // providers (square food photography).
        ObjectNode textPart = objectMapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        ObjectNode responseFormat = objectMapper.createObjectNode();
        responseFormat.put("type", "image");
        responseFormat.put("aspect_ratio", "1:1");

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", modelId);
        requestBody.putArray("input").add(textPart);
        requestBody.set("response_format", responseFormat);

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(GOOGLE_INTERACTIONS_URL, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            String base64Image = extractInteractionsImage(root);
            if (base64Image == null || base64Image.isBlank()) {
                throw new RuntimeException("Google Nano Banana returned no image data for model " + modelId
                        + "; response: " + truncate(response.getBody()));
            }
            return Base64.getDecoder().decode(base64Image);
        } catch (HttpStatusCodeException e) {
            throw new RuntimeException("Google Nano Banana API error " + e.getStatusCode() + ": " + e.getResponseBodyAsString(), e);
        }
    }

    /**
     * Pulls the base64 image out of an Interactions API response. Prefers the convenience
     * {@code output_image.data} field when present, then falls back to scanning the
     * {@code steps[].content[]} (and {@code steps[].summary[]}) arrays for the first block whose
     * {@code type} is {@code image}. Structure per
     * https://ai.google.dev/gemini-api/docs/image-generation
     */
    private String extractInteractionsImage(JsonNode root) {
        JsonNode convenience = root.path("output_image").path("data");
        if (convenience.isTextual() && !convenience.asText().isBlank()) {
            return convenience.asText();
        }
        JsonNode steps = root.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String fromContent = firstImageData(step.path("content"));
                if (fromContent != null) {
                    return fromContent;
                }
                // Interim "thought" images live under summary; only used as a last resort.
                String fromSummary = firstImageData(step.path("summary"));
                if (fromSummary != null) {
                    return fromSummary;
                }
            }
        }
        return null;
    }

    private String firstImageData(JsonNode blocks) {
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("image".equals(block.path("type").asText())) {
                    String data = block.path("data").asText(null);
                    if (data != null && !data.isBlank()) {
                        return data;
                    }
                }
            }
        }
        return null;
    }

    private String truncate(String s) {
        if (s == null) return "null";
        return s.length() > 500 ? s.substring(0, 500) + "…" : s;
    }

    /** Sniff the image format from magic bytes; defaults to image/png if unrecognized. */
    private String detectContentType(byte[] bytes) {
        if (bytes != null && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes != null && bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes != null && bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return "image/png";
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
