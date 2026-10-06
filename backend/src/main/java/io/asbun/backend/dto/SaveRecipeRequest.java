package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaveRecipeRequest {

    @URL
    private String imageUrl;

    private ImageModel imageModel = ImageModel.STABILITY_CORE;

    @NotBlank
    @Size(max = 200)
    private String title;

    @NotBlank
    @Size(max = 2000)
    private String description;

    @NotNull
    @Size(min = 1, max = 50)
    private List<@NotBlank @Size(max = 500) String> ingredients;

    // Optional structured breakdown of `ingredients` (Structured Ingredients spec §4). When
    // absent (older clients, or a recipe whose structure was lost), the service derives it from
    // `ingredients` via the parser so saved recipes always carry structure. Bounded to match
    // the string list so a recipe item stays well within DynamoDB's 400 KB limit.
    @Valid
    @Size(max = 50)
    private List<StructuredIngredientRequest> structuredIngredients;

    @NotNull
    @Size(min = 1, max = 50)
    private List<@NotBlank @Size(max = 1000) String> steps;

    // DietaryRestriction enum names the recipe satisfies (as produced at generation). Optional:
    // when absent, the service derives them from the ingredients so tags are always present.
    @Size(max = 20)
    private List<@Size(max = 50) String> dietaryTags;

    @NotNull
    private BedrockModel model;

    private Long textGenerationMs;
}
