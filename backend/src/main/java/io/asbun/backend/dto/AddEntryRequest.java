package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.asbun.backend.model.enums.MealSlot;
import io.asbun.backend.model.enums.RecipeSource;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Add an entry to a plan. {@code slot} and {@code source} are enum-typed, so an unknown value
 * is rejected as malformed JSON (400) rather than silently stored. The referenced recipe is
 * further validated for existence/accessibility in the service before persisting.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AddEntryRequest {

    @NotNull
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "must be an ISO date (yyyy-MM-dd)")
    private String date;

    @NotNull
    private MealSlot slot;

    @NotNull
    private RecipeSource source;

    @NotBlank
    @Size(max = 100)
    private String recipeId;

    /** Optional per-entry servings override; null = use the plan default. */
    @Positive
    @Max(50)
    private Integer servings;
}
