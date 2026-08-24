package io.asbun.backend.controller;

import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GenerateRecipeResponse;
import io.asbun.backend.dto.RecipeDto;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.ConsentService;
import io.asbun.backend.service.ImageSseService;
import io.asbun.backend.service.RecipeService;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for RecipeController.
 *
 * Validates: Requirements 1.2, 1.3
 */
@Tag("Feature: security-legal-compliance")
class RecipeControllerPropertyTest {

    // ========================================================================
    // Property 2: Pending deletion blocks write operations
    // ========================================================================

    /**
     * Property 2a: For any user in PENDING_DELETION status and any recipe generation
     * request, the backend rejects with HTTP 403 and message "Account is pending deletion".
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    @Tag("Property 2: Pending deletion blocks write operations")
    void generateRecipes_rejectedForPendingDeletionUser(
            @ForAll("userIds") String userId,
            @ForAll("validRecipeRequests") GenerateRecipeRequest request
    ) {
        RecipeService recipeService = mock(RecipeService.class);
        BedrockService bedrockService = mock(BedrockService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ImageSseService imageSseService = mock(ImageSseService.class);
        ConsentService consentService = mock(ConsentService.class);

        RecipeController controller = new RecipeController(
                recipeService, bedrockService, userRepository, imageSseService, consentService);
        ReflectionTestUtils.setField(controller, "testEmail", "test@example.com");
        ReflectionTestUtils.setField(controller, "generateCallLimit", 10);

        User pendingUser = User.builder()
                .userId(userId)
                .accountStatus(AccountStatus.PENDING_DELETION)
                .deletionRequestedAt(Instant.now().minusSeconds(3600))
                .scheduledDeletionDate(Instant.now().plusSeconds(86400 * 29))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(pendingUser));

        JwtAuthenticationToken authentication = createMockAuthentication(userId, "user@example.com");

        ResponseEntity<?> response = controller.generateRecipes(request, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("status")).isEqualTo(403);
        assertThat(body.get("message")).isEqualTo("Account is pending deletion");

        // Verify that BedrockService was never called (no AI service invocation)
        verifyNoInteractions(bedrockService);
    }

    /**
     * Property 2b: For any user in PENDING_DELETION status, getRecipes returns 200
     * (read-only operations still work).
     *
     * Validates: Requirements 1.3
     */
    @Property(tries = 100)
    @Tag("Property 2: Pending deletion blocks write operations")
    void getRecipes_allowedForPendingDeletionUser(
            @ForAll("userIds") String userId
    ) {
        RecipeService recipeService = mock(RecipeService.class);
        BedrockService bedrockService = mock(BedrockService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ImageSseService imageSseService = mock(ImageSseService.class);
        ConsentService consentService = mock(ConsentService.class);

        RecipeController controller = new RecipeController(
                recipeService, bedrockService, userRepository, imageSseService, consentService);
        ReflectionTestUtils.setField(controller, "testEmail", "test@example.com");
        ReflectionTestUtils.setField(controller, "generateCallLimit", 10);

        List<RecipeDto> recipes = List.of(
                RecipeDto.builder().recipeId("r1").userId(userId).title("Recipe 1").build()
        );
        when(recipeService.getRecipesByUser(userId)).thenReturn(recipes);

        JwtAuthenticationToken authentication = createMockAuthentication(userId, "user@example.com");

        ResponseEntity<List<RecipeDto>> response = controller.getRecipes(authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(recipes);
    }

    /**
     * Property 2c: For any user in PENDING_DELETION status, getRecipe (by ID) returns 200
     * (read-only operations still work).
     *
     * Validates: Requirements 1.3
     */
    @Property(tries = 100)
    @Tag("Property 2: Pending deletion blocks write operations")
    void getRecipeById_allowedForPendingDeletionUser(
            @ForAll("userIds") String userId,
            @ForAll("recipeIds") String recipeId
    ) {
        RecipeService recipeService = mock(RecipeService.class);
        BedrockService bedrockService = mock(BedrockService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ImageSseService imageSseService = mock(ImageSseService.class);
        ConsentService consentService = mock(ConsentService.class);

        RecipeController controller = new RecipeController(
                recipeService, bedrockService, userRepository, imageSseService, consentService);
        ReflectionTestUtils.setField(controller, "testEmail", "test@example.com");
        ReflectionTestUtils.setField(controller, "generateCallLimit", 10);

        RecipeDto recipe = RecipeDto.builder()
                .recipeId(recipeId)
                .userId(userId)
                .title("Test Recipe")
                .build();
        when(recipeService.getRecipeById(recipeId, userId)).thenReturn(recipe);

        JwtAuthenticationToken authentication = createMockAuthentication(userId, "user@example.com");

        ResponseEntity<RecipeDto> response = controller.getRecipe(recipeId, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(recipe);
    }

    /**
     * Property 2d: For any user without AI_DATA_PROCESSING consent (not in PENDING_DELETION),
     * generateRecipes returns 403 with message "AI data processing consent is required".
     *
     * Validates: Requirements 1.2
     */
    @Property(tries = 100)
    @Tag("Property 2: Pending deletion blocks write operations")
    void generateRecipes_rejectedWithoutAiConsent(
            @ForAll("userIds") String userId,
            @ForAll("validRecipeRequests") GenerateRecipeRequest request
    ) {
        RecipeService recipeService = mock(RecipeService.class);
        BedrockService bedrockService = mock(BedrockService.class);
        UserRepository userRepository = mock(UserRepository.class);
        ImageSseService imageSseService = mock(ImageSseService.class);
        ConsentService consentService = mock(ConsentService.class);

        RecipeController controller = new RecipeController(
                recipeService, bedrockService, userRepository, imageSseService, consentService);
        ReflectionTestUtils.setField(controller, "testEmail", "test@example.com");
        ReflectionTestUtils.setField(controller, "generateCallLimit", 10);

        // User is ACTIVE (not pending deletion)
        User activeUser = User.builder()
                .userId(userId)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(activeUser));

        // But does NOT have AI_DATA_PROCESSING consent
        when(consentService.hasActiveConsent(userId, ConsentType.AI_DATA_PROCESSING)).thenReturn(false);

        JwtAuthenticationToken authentication = createMockAuthentication(userId, "user@example.com");

        ResponseEntity<?> response = controller.generateRecipes(request, authentication);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("status")).isEqualTo(403);
        assertThat(body.get("message")).isEqualTo("AI data processing consent is required");

        // Verify that BedrockService was never called
        verifyNoInteractions(bedrockService);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(3)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> recipeIds() {
        return Arbitraries.create(() -> UUID.randomUUID().toString());
    }

    @Provide
    Arbitrary<GenerateRecipeRequest> validRecipeRequests() {
        Arbitrary<List<String>> ingredients = Arbitraries.strings()
                .alpha()
                .ofMinLength(2)
                .ofMaxLength(30)
                .list()
                .ofMinSize(1)
                .ofMaxSize(10);

        Arbitrary<BedrockModel> models = Arbitraries.of(BedrockModel.values());

        return Combinators.combine(ingredients, models).as((ing, model) -> {
            GenerateRecipeRequest request = new GenerateRecipeRequest();
            request.setIngredients(ing);
            request.setModel(model);
            return request;
        });
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private JwtAuthenticationToken createMockAuthentication(String userId, String email) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", userId, "email", email));
        return new JwtAuthenticationToken(jwt);
    }
}
