package io.asbun.backend.service;

import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RecipeService {

    private final RecipeRepository recipeRepository;

    public RecipeDto saveRecipe(SaveRecipeRequest request, String userId) {
        Recipe recipe = Recipe.builder()
                .recipeId(UUID.randomUUID().toString())
                .userId(userId)
                .title(request.getTitle())
                .description(request.getDescription())
                .ingredients(request.getIngredients())
                .steps(request.getSteps())
                .imageUrl(request.getImageUrl())
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
        return RecipeDto.builder()
                .recipeId(recipe.getRecipeId())
                .userId(recipe.getUserId())
                .title(recipe.getTitle())
                .description(recipe.getDescription())
                .ingredients(recipe.getIngredients())
                .steps(recipe.getSteps())
                .imageUrl(recipe.getImageUrl())
                .model(recipe.getModel())
                .createdAt(recipe.getCreatedAt())
                .build();
    }
}
