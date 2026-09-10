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

    // Recipes whose S3 image was removed (historically, by the now-removed 90-day lifecycle rule)
    // still carry an imageUrl key in DynamoDB. When a read detects the missing object it kicks off
    // a (paid) regeneration. To stop a burst of list reads from firing many regenerations for the
    // same recipe, we record the time we last triggered one per recipeId and suppress re-triggers
    // within a cooldown window that comfortably covers the async generation + retries.
    private final java.util.concurrent.ConcurrentMap<String, Long> lastRegenerationAt =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REGENERATION_COOLDOWN_MS = java.time.Duration.ofMinutes(5).toMillis();

    private void maybeRegenerateImage(Recipe recipe) {
        if (recipe.getImageModel() == null) {
            // No image model recorded (older recipes) — we can't know how to regenerate. Leave it
            // imageless rather than guessing a model.
            return;
        }
        long now = System.currentTimeMillis();
        // Suppress if we triggered a regeneration for this recipe within the cooldown window.
        // merge() lets us atomically check-and-set: keep the existing timestamp if it's still
        // within the window, otherwise take the new one and signal that we should trigger.
        Long previous = lastRegenerationAt.get(recipe.getRecipeId());
        if (previous != null && (now - previous) < REGENERATION_COOLDOWN_MS) {
            return;
        }
        // compute() ensures only one concurrent caller wins the trigger for this recipe.
        boolean[] shouldTrigger = {false};
        lastRegenerationAt.compute(recipe.getRecipeId(), (id, ts) -> {
            if (ts != null && (now - ts) < REGENERATION_COOLDOWN_MS) {
                return ts; // another thread just triggered; leave its timestamp untouched
            }
            shouldTrigger[0] = true;
            return now;
        });
        if (!shouldTrigger[0]) {
            return;
        }
        log.info("Triggering image regeneration for recipe {} (missing S3 object)", recipe.getRecipeId());
        asyncImageService.generateAndUpdateRecipe(
                recipe.getRecipeId(), recipe.getTitle(), recipe.getImageModel());
    }

    private RecipeDto toDto(Recipe recipe) {
        String imageUrl = null;
        if (recipe.getImageUrl() != null) {
            if (s3Service.objectExists(recipe.getImageUrl())) {
                imageUrl = s3Service.generatePresignedUrl(recipe.getImageUrl());
            } else {
                // Fix #4: the object is gone — don't hand out a doomed presigned URL. The DTO
                // reports imageUrl=null so the client shows its "no image" state instead of a
                // broken image.
                log.info("S3 object missing for recipe {} (key {}) — returning null imageUrl", recipe.getRecipeId(), recipe.getImageUrl());
                // Fix #2: self-heal by regenerating the image when we know how (imageModel set).
                maybeRegenerateImage(recipe);
            }
        }
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
