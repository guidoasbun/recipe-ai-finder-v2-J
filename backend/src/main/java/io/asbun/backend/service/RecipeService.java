package io.asbun.backend.service;

import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;
    private final ImageGenerationService imageGenerationService;
    private final S3Service s3Service;

    public RecipeDto saveRecipe(SaveRecipeRequest request, String userId) {
        String recipeId = UUID.randomUUID().toString();
        String imageUrl = null;
        try {
            imageUrl = imageGenerationService.generateAndUploadImage(recipeId, request.getTitle(), request.getImageModel());
        } catch (Exception e) {
            log.warn("Image generation failed for recipe {}, saving without image: {}", recipeId, e.getMessage());
        }

        Recipe recipe = Recipe.builder()
                .recipeId(recipeId)
                .userId(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .ingredients(request.getIngredients())
                .steps(request.getSteps())
                .imageUrl(imageUrl)
                .model(request.getModel())
                .createdAt(Instant.now())
                .build();

        recipeRepository.save(recipe);
        return toDto(recipe);
    }

    public List<RecipeDto> getRecipesByUser(String userId) {
        return recipeRepository.findByUserId(userId)
                .stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public RecipeDto getRecipeById(String recipeId, String userId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found: " + recipeId));

        if (!recipe.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Recipe not found: " + recipeId);
        }

        return toDto(recipe);
    }

    public void deleteRecipe(String recipeId, String userId) {
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipe not found: " + recipeId));

        if (!recipe.getUserId().equals(userId)) {
            throw new ResourceNotFoundException("Recipe not found: " + recipeId);
        }

        recipeRepository.delete(recipeId);
    }

    private RecipeDto toDto(Recipe recipe) {
        String imageUrl = recipe.getImageUrl() != null
                ? s3Service.generatePresignedUrl(recipe.getImageUrl())
                : null;
        return RecipeDto.builder()
                .recipeId(recipe.getRecipeId())
                .userId(recipe.getUserId())
                .title(recipe.getTitle())
                .description(recipe.getDescription())
                .ingredients(recipe.getIngredients())
                .steps(recipe.getSteps())
                .imageUrl(imageUrl)
                .model(recipe.getModel())
                .createdAt(recipe.getCreatedAt())
                .build();
    }
}
