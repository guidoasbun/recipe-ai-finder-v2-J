package io.asbun.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.List;

/**
 * A shared, read-only catalog recipe sourced from an open dataset (Phase 1: TheMealDB).
 * Distinct from {@link Recipe}, which is per-user and mutable. Stored in its own table
 * ({@code dynamodb.catalog-table}).
 *
 * <p>{@code dietaryTags} and {@code embedding} are computed once at ingestion and persisted
 * so a future OpenSearch backend can reindex from this table without re-computation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public class CatalogRecipe {

    /** Deterministic id derived from the source recipe, so re-ingest overwrites rather than duplicates. */
    private String catalogRecipeId;

    private String title;
    private String description;
    private List<String> ingredients;
    private List<String> steps;
    private String imageUrl;

    /** Subset of DietaryRestriction enum names the recipe satisfies. */
    private List<String> dietaryTags;

    /** Precomputed lowercase title + description + ingredients, used for keyword matching. */
    private String searchText;

    /** Titan Text Embeddings V2 vector (1024 dims). Stored as Double for DynamoDB compatibility. */
    private List<Double> embedding;

    // Source attribution (dataset licensing)
    private String sourceName;
    private String sourceUrl;
    private String sourceLicense;
    private String sourceCountry;

    private Instant ingestedAt;

    @DynamoDbPartitionKey
    public String getCatalogRecipeId() {
        return catalogRecipeId;
    }
}
