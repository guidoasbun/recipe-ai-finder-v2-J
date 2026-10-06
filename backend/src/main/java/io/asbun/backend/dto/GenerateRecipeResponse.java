package io.asbun.backend.dto;

import io.asbun.backend.model.StructuredIngredient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateRecipeResponse {

    private String title;
    private String description;
    private List<String> ingredients;
    /**
     * Structured breakdown of {@link #ingredients}, same length/order (one entry per raw
     * string). Populated from the model's structured output when well-formed, else derived
     * from the display strings by the server-side parser (set in task 5).
     */
    private List<StructuredIngredient> structuredIngredients;
    private List<String> steps;
    private Long generationMs;

    /** DietaryRestriction enum names the recipe satisfies, derived from its ingredients. */
    private List<String> dietaryTags;
}
