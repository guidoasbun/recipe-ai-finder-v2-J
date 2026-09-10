package io.asbun.backend.controller;

import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.dto.SaveRecipeRequest;
import io.asbun.backend.exception.RateLimitExceededException;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.ingest.DietaryTagger;
import io.asbun.backend.metrics.MetricsService;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.SavedRecipeSearchQuery;
import io.asbun.backend.search.SavedRecipeSearchResults;
import io.asbun.backend.search.SavedRecipeSearchService;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.ConsentService;
import io.asbun.backend.service.ImageSseService;
import io.asbun.backend.service.RecipeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
    private final ConsentService consentService;
    private final MetricsService metricsService;
    private final SavedRecipeSearchService savedRecipeSearchService;
    private final DietaryTagger dietaryTagger;

    @Value("${testuser.email}")
    private String testEmail;

    @Value("${testuser.generate-call-limit}")
    private int generateCallLimit;

    @Value("${recipes.search.page-size-default:6}")
    private int defaultPageSize;

    @Value("${recipes.search.page-size-max:50}")
    private int maxPageSize;

    @PostMapping
    public ResponseEntity<RecipeDto> saveRecipe(
            @Valid @RequestBody SaveRecipeRequest request,
            Authentication authentication) {
        RecipeDto recipe = recipeService.saveRecipe(request, getUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(recipe);
    }

    /**
     * Lists the caller's saved recipes, paginated and optionally filtered by a search term.
     *
     * <p>{@code q} matches (case-insensitive) the recipe title, description, or ingredients;
     * blank/absent means no filter. Results are newest-first. {@code page} is 0-based and
     * {@code pageSize} defaults to {@code recipes.search.page-size-default}, both clamped to safe
     * bounds. The response carries the page of items plus {@code page}/{@code pageSize}/{@code
     * totalMatches} so the UI can render pagination controls. Only the caller's own recipes are
     * ever returned.
     */
    @GetMapping
    public ResponseEntity<SavedRecipeSearchResults> getRecipes(
            @RequestParam(required = false) @Size(max = 200) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer pageSize,
            Authentication authentication) {
        int size = pageSize == null ? defaultPageSize : pageSize;
        size = Math.max(1, Math.min(size, maxPageSize));
        int safePage = Math.max(0, page);

        SavedRecipeSearchQuery query =
                new SavedRecipeSearchQuery(getUserId(authentication), q, safePage, size);
        return ResponseEntity.ok(savedRecipeSearchService.search(query));
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
    public ResponseEntity<?> generateRecipes(
            @Valid @RequestBody GenerateRecipeRequest request,
            Authentication authentication) {
        String userId = getUserId(authentication);
        String email = getEmail(authentication);

        // Check if account is pending deletion or failed deletion
        var userOpt = userRepository.findById(userId);
        if (userOpt.isPresent()
                && (userOpt.get().getAccountStatus() == AccountStatus.PENDING_DELETION
                    || userOpt.get().getAccountStatus() == AccountStatus.DELETION_FAILED)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "status", 403,
                            "message", "Account is pending deletion",
                            "timestamp", java.time.Instant.now().toString()));
        }

        // Check AI_DATA_PROCESSING consent
        if (!consentService.hasActiveConsent(userId, ConsentType.AI_DATA_PROCESSING)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "status", 403,
                            "message", "AI data processing consent is required",
                            "timestamp", java.time.Instant.now().toString()));
        }

        if (testEmail.equals(email)) {
            int used = userRepository.findById(userId)
                    .map(u -> u.getGenerateCallsUsed() == null ? 0 : u.getGenerateCallsUsed())
                    .orElse(0);
            if (used >= generateCallLimit) {
                throw new RateLimitExceededException(
                        "Demo account has reached the generation limit of " + generateCallLimit + " calls.");
            }
        }

        List<String> dietaryRestrictions = userOpt
                .map(u -> u.getDietaryRestrictions() == null
                        ? java.util.Collections.<String>emptyList()
                        : u.getDietaryRestrictions())
                .orElse(java.util.Collections.emptyList());

        String modelName = request.getModel() == null ? "unknown" : request.getModel().name();
        long start = System.currentTimeMillis();
        List<GenerateRecipeResponse> recipes;
        try {
            recipes = bedrockService.generateRecipes(
                    request.getIngredients(), dietaryRestrictions, request.getModel());
        } catch (RuntimeException e) {
            // Telemetry only — do not change behavior: record the failure and rethrow.
            metricsService.count("BedrockFailure", 1.0, "Model", modelName);
            throw e;
        }
        long generationMs = System.currentTimeMillis() - start;
        recipes.forEach(r -> {
            r.setGenerationMs(generationMs);
            // Derive dietary tags from the actual generated ingredients (same tagger the catalog
            // uses), so tags reflect the recipe itself and stay consistent across the app. These
            // flow to the card and are persisted verbatim on save.
            r.setDietaryTags(dietaryTagger.tag(r.getIngredients()));
        });
        metricsService.latencyMs("BedrockLatencyMs", generationMs, "Model", modelName);

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
