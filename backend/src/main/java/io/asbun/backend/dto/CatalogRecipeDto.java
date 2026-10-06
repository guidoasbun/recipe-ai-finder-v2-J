package io.asbun.backend.dto;

import io.asbun.backend.model.StructuredIngredient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Public view of a catalog recipe. Excludes internal fields (searchText, embedding).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogRecipeDto {

    private String catalogRecipeId;
    private String title;
    private String description;
    private List<String> ingredients;
    /** Structured breakdown of {@link #ingredients}; additive, absent/empty until backfilled. */
    private List<StructuredIngredient> structuredIngredients;
    private List<String> steps;
    private String imageUrl;
    private List<String> dietaryTags;
    private String sourceName;
    private String sourceUrl;
    private String sourceLicense;
    private String sourceCountry;
}
