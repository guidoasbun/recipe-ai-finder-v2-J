package io.asbun.backend.dto;

import io.asbun.backend.model.enums.BedrockModel;
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
    private List<String> ingredients;
    private List<String> steps;
    private BedrockModel model;
    private Instant createdAt;
}
