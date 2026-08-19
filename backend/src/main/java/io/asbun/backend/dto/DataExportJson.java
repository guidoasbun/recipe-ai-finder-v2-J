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
}
