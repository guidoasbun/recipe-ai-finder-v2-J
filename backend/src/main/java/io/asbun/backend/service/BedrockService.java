package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.model.enums.BedrockModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BedrockService {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<GenerateRecipeResponse> generateRecipes(List<String> ingredients, BedrockModel model) {
        String prompt = buildPrompt(ingredients);
        String requestBody = buildRequestBody(model, prompt);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(model.getModelId())
                .body(SdkBytes.fromUtf8String(requestBody))
                .contentType("application/json")
                .accept("application/json")
                .build();

        InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
        return parseResponse(model, response.body().asUtf8String());
    }

    private String buildPrompt(List<String> ingredients) {
        return String.format(
            "You are a professional chef. Generate exactly 3 creative recipes using some or all of these ingredients: %s. " +
            "Respond ONLY with a valid JSON array of 3 recipe objects. Each object must have these exact fields: " +
            "\"title\" (string), \"description\" (string, 1-2 sentences), " +
            "\"ingredients\" (array of strings with quantities), \"steps\" (array of strings). " +
            "Do not include any text before or after the JSON array.",
            String.join(", ", ingredients)
        );
    }

    private String buildRequestBody(BedrockModel model, String prompt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            switch (model) {
                case CLAUDE_HAIKU, CLAUDE_SONNET -> {
                    body.put("anthropic_version", "bedrock-2023-05-31");
                    body.put("max_tokens", 4096);
                    ArrayNode messages = body.putArray("messages");
                    ObjectNode message = messages.addObject();
                    message.put("role", "user");
                    message.put("content", prompt);
                }
                case AMAZON_TITAN -> {
                    body.put("inputText", prompt);
                    ObjectNode config = body.putObject("textGenerationConfig");
                    config.put("maxTokenCount", 4096);
                    config.put("temperature", 0.7);
                }
                case LLAMA3 -> {
                    body.put("prompt", prompt);
                    body.put("max_gen_len", 4096);
                    body.put("temperature", 0.7);
                }
                case NOVA_LITE -> {
                    ArrayNode messages = body.putArray("messages");
                    ObjectNode message = messages.addObject();
                    message.put("role", "user");
                    ArrayNode content = message.putArray("content");
                    content.addObject().put("text", prompt);
                }
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Bedrock request", e);
        }
    }

    private List<GenerateRecipeResponse> parseResponse(BedrockModel model, String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String text = switch (model) {
                case CLAUDE_HAIKU, CLAUDE_SONNET ->
                    root.path("content").get(0).path("text").asText();
                case AMAZON_TITAN ->
                    root.path("results").get(0).path("outputText").asText();
                case LLAMA3 ->
                    root.path("generation").asText();
                case NOVA_LITE ->
                    root.path("output").path("message").path("content").get(0).path("text").asText();
            };

            // Models sometimes add text before/after the JSON array despite instructions
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']') + 1;
            JsonNode recipes = objectMapper.readTree(text.substring(start, end));

            List<GenerateRecipeResponse> result = new ArrayList<>();
            for (JsonNode recipe : recipes) {
                List<String> ingredients = new ArrayList<>();
                recipe.path("ingredients").forEach(i -> ingredients.add(i.asText()));

                List<String> steps = new ArrayList<>();
                recipe.path("steps").forEach(s -> steps.add(s.asText()));

                result.add(GenerateRecipeResponse.builder()
                        .title(recipe.path("title").asText())
                        .description(recipe.path("description").asText())
                        .ingredients(ingredients)
                        .steps(steps)
                        .build());
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Bedrock response", e);
        }
    }
}
