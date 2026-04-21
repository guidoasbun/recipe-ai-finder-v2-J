package io.asbun.backend.controller;

import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.ImageGenerationService;
import io.asbun.backend.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService recipeService;
    private final BedrockService bedrockService;
    private final ImageGenerationService imageGenerationService;

    @PostMapping
    public ResponseEntity<RecipeDto> saveRecipe(
            @Valid @RequestBody SaveRecipeRequest request,
            Authentication authentication) {
        RecipeDto recipe = recipeService.saveRecipe(request, getUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(recipe);
    }

    @GetMapping
    public ResponseEntity<List<RecipeDto>> getRecipes(Authentication authentication) {
        return ResponseEntity.ok(recipeService.getRecipesByUser(getUserId(authentication)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RecipeDto> getRecipe(
            @PathVariable String id,
            Authentication authentication) {
        return ResponseEntity.ok(recipeService.getRecipeById(id, getUserId(authentication)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecipe(
            @PathVariable String id,
            Authentication authentication) {
        recipeService.deleteRecipe(id, getUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate")
    public SseEmitter generateRecipes(
            @Valid @RequestBody GenerateRecipeRequest request,
            Authentication authentication) {
        String userId = getUserId(authentication);
        SseEmitter emitter = new SseEmitter(120_000L);

        CompletableFuture.runAsync(() -> {
            try {
                List<GenerateRecipeResponse> recipes = bedrockService.generateRecipes(
                        request.getIngredients(), request.getModel());

                for (int i = 0; i < recipes.size(); i++) {
                    GenerateRecipeResponse recipe = recipes.get(i);

                    // Emit recipe without image first
                    Map<String, Object> recipeEvent = new HashMap<>();
                    recipeEvent.put("index", i);
                    recipeEvent.put("recipe", recipe);
                    emitter.send(SseEmitter.event().name("recipe").data(recipeEvent));

                    // Generate image and emit update
                    try {
                        String recipeId = UUID.randomUUID().toString();
                        String imageKey = imageGenerationService.generateAndUploadImage(
                                recipeId, recipe.getTitle());

                        Map<String, Object> imageEvent = new HashMap<>();
                        imageEvent.put("index", i);
                        imageEvent.put("imageKey", imageKey);
                        emitter.send(SseEmitter.event().name("image").data(imageEvent));
                    } catch (Exception e) {
                        // Image generation failed — continue without image
                    }
                }

                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }
}
