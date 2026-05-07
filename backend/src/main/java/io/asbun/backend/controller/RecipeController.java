package io.asbun.backend.controller;

import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.exception.RateLimitExceededException;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.ImageSseService;
import io.asbun.backend.service.RecipeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService recipeService;
    private final BedrockService bedrockService;
    private final UserRepository userRepository;
    private final ImageSseService imageSseService;

    @Value("${testuser.email}")
    private String testEmail;

    @Value("${testuser.generate-call-limit}")
    private int generateCallLimit;

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
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,36}$") String id,
            Authentication authentication) {
        return ResponseEntity.ok(recipeService.getRecipeById(id, getUserId(authentication)));
    }

    @GetMapping("/{id}/image-stream")
    public SseEmitter streamImage(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,36}$") String id,
            Authentication authentication) {
        recipeService.getRecipeById(id, getUserId(authentication));
        return imageSseService.subscribe(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRecipe(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,36}$") String id,
            Authentication authentication) {
        recipeService.deleteRecipe(id, getUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/generate")
    public ResponseEntity<List<GenerateRecipeResponse>> generateRecipes(
            @Valid @RequestBody GenerateRecipeRequest request,
            Authentication authentication) {
        String userId = getUserId(authentication);
        String email = getEmail(authentication);

        if (testEmail.equals(email)) {
            int used = userRepository.findById(userId)
                    .map(u -> u.getGenerateCallsUsed() == null ? 0 : u.getGenerateCallsUsed())
                    .orElse(0);
            if (used >= generateCallLimit) {
                throw new RateLimitExceededException(
                        "Demo account has reached the generation limit of " + generateCallLimit + " calls.");
            }
        }

        long start = System.currentTimeMillis();
        List<GenerateRecipeResponse> recipes = bedrockService.generateRecipes(
                request.getIngredients(), request.getModel());
        long generationMs = System.currentTimeMillis() - start;
        recipes.forEach(r -> r.setGenerationMs(generationMs));

        if (testEmail.equals(email)) {
            userRepository.atomicIncrementGenerateCalls(userId);
        }

        return ResponseEntity.ok(recipes);
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }

    private String getEmail(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().getOrDefault("email", "");
    }
}
