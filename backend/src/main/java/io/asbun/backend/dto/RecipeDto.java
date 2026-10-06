package io.asbun.backend.dto;

import io.asbun.backend.model.StructuredIngredient;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecipeDto {
    
    private String recipeId;
    private String userId;
    private String title;
    private String description;
    private String imageUrl;
    private Integer imageWidth;
    private Integer imageHeight;
    private String imageType;
    private Long imageSizeBytes;
    private Long imageGenerationMs;
    private List<String> ingredients;
    /** Structured breakdown of {@link #ingredients}; additive, absent/empty until backfilled. */
    private List<StructuredIngredient> structuredIngredients;
    private List<String> steps;
    private List<String> dietaryTags;
    private BedrockModel model;
    private ImageModel imageModel;
    private Long textGenerationMs;
    private Instant createdAt;
}
