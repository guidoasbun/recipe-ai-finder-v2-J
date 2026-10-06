package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Client-submitted structured ingredient on a save request (Structured Ingredients spec §4).
 * Optional: the save path derives the structured list from the string {@code ingredients} when
 * this is absent, so the string list stays the only required ingredient input.
 *
 * <p>{@code quantity} and {@code unit} are nullable (an ingredient like "salt to taste" has
 * neither); {@code item} and {@code raw} are required and non-blank, matching the model's
 * {@link io.asbun.backend.model.StructuredIngredient} invariant.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StructuredIngredientRequest {

    /** Parsed amount; null when the source text has no quantity. Non-negative when present. */
    @PositiveOrZero
    private Double quantity;

    /** Unit token (e.g. "cup"); null when there is no unit. */
    @Size(max = 50)
    private String unit;

    /** Ingredient name without the amount. Required, non-blank. */
    @NotBlank
    @Size(max = 500)
    private String item;

    /** Original ingredient string verbatim. Required, non-blank. */
    @NotBlank
    @Size(max = 500)
    private String raw;
}
