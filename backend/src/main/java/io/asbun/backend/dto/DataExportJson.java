package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class DataExportJson {

    private Instant exportedAt;
    private UserExportData user;
    private List<RecipeExportData> recipes;
    private List<MealPlanExportData> mealPlans;
    private List<String> missingImages;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserExportData {
        private String email;
        private String username;
        private Instant createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RecipeExportData {
        private String recipeId;
        private String title;
        private String description;
        private List<String> ingredients;
        private List<String> steps;
        private String model;
        private String imageModel;
        private Long textGenerationMs;
        private Long imageGenerationMs;
        private Instant createdAt;
        private String imageS3Key;
    }

    /**
     * A user's meal plan. Entries reference recipes by id (no recipe content is copied — the
     * recipes themselves are exported separately in {@code recipes}).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MealPlanExportData {
        private String mealPlanId;
        private String name;
        private String startDate;
        private String endDate;
        private Integer servings;
        private List<MealPlanEntryExportData> entries;
        private Instant createdAt;
        private Instant updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MealPlanEntryExportData {
        private String entryId;
        private String date;
        private String slot;
        private Integer servings;
        private Integer spanDays;
        private String recipeSource;
        private String recipeId;
    }
}
