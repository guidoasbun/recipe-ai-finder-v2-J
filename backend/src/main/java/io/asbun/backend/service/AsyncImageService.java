package io.asbun.backend.service;

import io.asbun.backend.dto.ImageUploadResult;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.enums.ImageModel;
import io.asbun.backend.repository.RecipeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncImageService {

    private final ImageGenerationService imageGenerationService;
    private final RecipeRepository recipeRepository;
    private final ImageSseService imageSseService;

    @Async("imageGenerationExecutor")
    public void generateAndUpdateRecipe(String recipeId, String title, ImageModel imageModel) {
        int maxAttempts = 3;
        long delayMs = 2000;
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ImageUploadResult result = imageGenerationService.generateAndUploadImage(recipeId, title, imageModel);

                Recipe recipe = recipeRepository.findById(recipeId).orElse(null);
                if (recipe == null) {
                    log.warn("Recipe {} not found after image generation — skipping image update", recipeId);
                    return;
                }

                recipe.setImageUrl(result.s3Key());
                recipe.setImageWidth(result.width());
                recipe.setImageHeight(result.height());
                recipe.setImageType(result.imageType());
                recipe.setImageSizeBytes(result.imageSizeBytes());
                recipe.setImageGenerationMs(result.generationMs());

                recipeRepository.save(recipe);
                log.info("Image updated for recipe {}", recipeId);
                imageSseService.notifyImageReady(recipeId);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("Image generation attempt {}/{} failed for recipe {}: {}", attempt, maxAttempts, recipeId, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    delayMs *= 2;
                }
            }
        }

        log.warn("Image generation failed after {} attempts for recipe {}: {}", maxAttempts, recipeId, lastException.getMessage(), lastException);
    }
}
