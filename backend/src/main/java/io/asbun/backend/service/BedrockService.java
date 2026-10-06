package io.asbun.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.ingest.IngredientParser;
import io.asbun.backend.model.StructuredIngredient;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.DietaryRestriction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BedrockService {

    private final BedrockRuntimeClient bedrockRuntimeClient;
    /** Fallback/normalizer that derives structured ingredients from the display strings. */
    private final IngredientParser ingredientParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<GenerateRecipeResponse> generateRecipes(List<String> ingredients,
                                                        List<String> dietaryRestrictions,
                                                        BedrockModel model) {
        String prompt = buildPrompt(ingredients, dietaryRestrictions);
        String requestBody = buildRequestBody(model, prompt);

        InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(model.getModelId())
                .body(SdkBytes.fromUtf8String(requestBody))
                .contentType("application/json")
                .accept("application/json")
                .build();

        int maxAttempts = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
                return parseResponse(model, response.body().asUtf8String());
            } catch (Exception e) {
                lastException = e;
                log.warn("Recipe generation attempt {}/{} failed: {}", attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        log.error("Bedrock generation failed after {} attempts", maxAttempts, lastException);
        throw new RuntimeException("Failed to generate recipes after " + maxAttempts + " attempts", lastException);
    }

    private String buildPrompt(List<String> ingredients, List<String> dietaryRestrictions) {
        String basePrompt = String.format(
            "You are a professional chef. Generate exactly 3 creative recipes using some or all of these ingredients: %s. " +
            "Assume the user already has basic pantry staples at home such as salt, pepper, garlic powder, onion powder, " +
            "paprika, cumin, oregano, chili flakes, flour, sugar, butter, olive oil, vegetable oil, vinegar, and soy sauce. " +
            "You may include these staples in the recipes without the user needing to list them, " +
            "but ONLY when they do not conflict with the user's dietary restrictions stated below. " +
            "Any dietary restriction always overrides this pantry-staple permission. ",
            String.join(", ", ingredients)
        );

        String dietaryClause = buildDietaryClause(dietaryRestrictions);

        String outputInstructions =
            "Respond ONLY with a valid JSON array of 3 recipe objects. Each object must have these exact fields: " +
            "\"title\" (string), \"description\" (string, 1-2 sentences), " +
            "\"ingredients\" (array of strings with quantities), " +
            "\"structuredIngredients\" (array of objects, one per entry in \"ingredients\", in the same " +
            "order and the same length, each with \"quantity\" (number or null), \"unit\" (string or null, " +
            "e.g. \"cup\", \"tsp\", \"g\"), and \"item\" (string, the ingredient name without the amount)), " +
            "\"steps\" (array of strings). " +
            "Do not include any text before or after the JSON array.";

        return basePrompt + dietaryClause + outputInstructions;
    }

    private String buildDietaryClause(List<String> dietaryRestrictions) {
        if (dietaryRestrictions == null || dietaryRestrictions.isEmpty()) {
            return "";
        }

        String names = dietaryRestrictions.stream()
                .map(this::toDisplayName)
                .collect(Collectors.joining(", "));

        return String.format(
            "IMPORTANT DIETARY CONSTRAINTS: The user has the following dietary restrictions: %s. " +
            "You MUST NOT include any ingredients or preparation methods that violate these restrictions. " +
            "Every recipe must fully comply with all listed dietary restrictions. ",
            names
        );
    }

    private String toDisplayName(String restriction) {
        try {
            return DietaryRestriction.valueOf(restriction).getDisplayName();
        } catch (IllegalArgumentException | NullPointerException e) {
            // Fall back to the raw value if it is not a recognized enum constant.
            return restriction;
        }
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
                    ArrayNode messages = body.putArray("messages");
                    ObjectNode message = messages.addObject();
                    message.put("role", "user");
                    ArrayNode content = message.putArray("content");
                    content.addObject().put("text", prompt);
                }
                case LLAMA3 -> {
                    String llama3Prompt = "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\n"
                            + prompt
                            + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n";
                    body.put("prompt", llama3Prompt);
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
                    root.path("output").path("message").path("content").get(0).path("text").asText();
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
                        .structuredIngredients(resolveStructured(recipe, ingredients))
                        .steps(steps)
                        .build());
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Bedrock response", e);
        }
    }

    /**
     * Resolves a recipe's structured ingredients. Uses the model's {@code structuredIngredients}
     * array only when it is present and its length matches the display {@code ingredients} count
     * (the positional-correspondence invariant, spec §1.3), binding each entry's {@code raw} to
     * the corresponding display string. Otherwise — absent, malformed, or length mismatch — it
     * derives the whole list from the display strings via {@link IngredientParser}, so a model
     * that ignores the structured instruction never breaks generation.
     */
    private List<StructuredIngredient> resolveStructured(JsonNode recipe, List<String> ingredients) {
        JsonNode node = recipe.path("structuredIngredients");
        if (node.isArray() && node.size() == ingredients.size()) {
            try {
                List<StructuredIngredient> structured = new ArrayList<>(ingredients.size());
                for (int i = 0; i < ingredients.size(); i++) {
                    JsonNode e = node.get(i);
                    String raw = ingredients.get(i);
                    Double quantity = e.hasNonNull("quantity") ? e.path("quantity").asDouble() : null;
                    String unit = e.hasNonNull("unit") && !e.path("unit").asText().isBlank()
                            ? e.path("unit").asText() : null;
                    String item = e.hasNonNull("item") && !e.path("item").asText().isBlank()
                            ? e.path("item").asText()
                            // Blank/absent item for this one entry: parse it from its raw string.
                            : ingredientParser.parse(raw).getItem();
                    structured.add(StructuredIngredient.builder()
                            .quantity(quantity)
                            .unit(unit)
                            .item(item)
                            .raw(raw)
                            .build());
                }
                return structured;
            } catch (RuntimeException malformed) {
                log.debug("Malformed structuredIngredients from model; falling back to parser: {}",
                        malformed.getMessage());
            }
        }
        // Absent, not an array, length mismatch, or malformed → derive from the display strings.
        return ingredientParser.parseAll(ingredients);
    }
}
