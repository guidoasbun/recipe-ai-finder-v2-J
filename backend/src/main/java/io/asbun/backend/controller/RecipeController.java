package io.asbun.backend.controller;

import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.RecipeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recipes")
@RequiredArgsConstructor
public class RecipeController {

    private final RecipeService recipeService;
    private final BedrockService bedrockService;

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
    public ResponseEntity<List<GenerateRecipeResponse>> generateRecipes(
            @Valid @RequestBody GenerateRecipeRequest request,
            Authentication authentication) {
        List<GenerateRecipeResponse> recipes = bedrockService.generateRecipes(
                request.getIngredients(), request.getModel());
        return ResponseEntity.ok(recipes);
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }
}
