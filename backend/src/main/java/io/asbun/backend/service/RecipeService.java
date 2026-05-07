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
    private final AsyncImageService asyncImageService;
    private final S3Service s3Service;

    public RecipeDto saveRecipe(SaveRecipeRequest request, String userId) {
        String recipeId = UUID.randomUUID().toString();

        Recipe recipe = Recipe.builder()
                .recipeId(recipeId)
                .userId(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .ingredients(request.getIngredients())
                .steps(request.getSteps())
                .model(request.getModel())
                .imageModel(request.getImageModel())
                .textGenerationMs(request.getTextGenerationMs())
                .createdAt(Instant.now())
                .build();

        recipeRepository.save(recipe);
        asyncImageService.generateAndUpdateRecipe(recipeId, request.getTitle(), request.getImageModel());
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

        if (recipe.getImageUrl() != null) {
            s3Service.deleteImage(recipe.getImageUrl());
        }
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
                .imageWidth(recipe.getImageWidth())
                .imageHeight(recipe.getImageHeight())
                .imageType(recipe.getImageType())
                .imageSizeBytes(recipe.getImageSizeBytes())
                .imageGenerationMs(recipe.getImageGenerationMs())
                .model(recipe.getModel())
                .imageModel(recipe.getImageModel())
                .textGenerationMs(recipe.getTextGenerationMs())
                .createdAt(recipe.getCreatedAt())
                .build();
    }
}
