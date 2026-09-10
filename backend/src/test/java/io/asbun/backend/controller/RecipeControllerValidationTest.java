package io.asbun.backend.controller;

import io.asbun.backend.exception.GlobalExceptionHandler;
import io.asbun.backend.metrics.MetricsService;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.SavedRecipeSearchQuery;
import io.asbun.backend.search.SavedRecipeSearchResults;
import io.asbun.backend.search.SavedRecipeSearchService;
import io.asbun.backend.service.BedrockService;
import io.asbun.backend.service.ConsentService;
import io.asbun.backend.service.ImageSseService;
import io.asbun.backend.service.RecipeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer (MockMvc) test proving that the {@code @Size(max = 200)} constraint on the
 * {@code q} search param of {@code GET /api/recipes} is actually enforced by Spring's
 * {@code @Validated} method-validation layer (returning 400), not just declared. Direct
 * controller-method calls bypass this layer, so this exercises it through the real dispatch.
 *
 * Feature: saved-recipe-search
 * Validates: Requirements 3.7, 4.1
 */
class RecipeControllerValidationTest {

    private static final String USER_ID = "user-123";

    private MockMvc mockMvc;
    private JwtAuthenticationToken principal;

    @BeforeEach
    void setUp() {
        SavedRecipeSearchService savedRecipeSearchService = mock(SavedRecipeSearchService.class);
        when(savedRecipeSearchService.search(any(SavedRecipeSearchQuery.class)))
                .thenReturn(new SavedRecipeSearchResults(List.of(), 0, 6, 0));

        RecipeController controller = new RecipeController(
                mock(RecipeService.class),
                mock(BedrockService.class),
                mock(UserRepository.class),
                mock(ImageSseService.class),
                mock(ConsentService.class),
                mock(MetricsService.class),
                savedRecipeSearchService,
                mock(io.asbun.backend.ingest.DietaryTagger.class));
        ReflectionTestUtils.setField(controller, "defaultPageSize", 6);
        ReflectionTestUtils.setField(controller, "maxPageSize", 50);

        // Apply @Validated method-parameter validation the same way Spring does at runtime: wrap
        // the controller in a validation-enforcing proxy. Standalone MockMvc does not do this on
        // its own, so without the post-processor the @Size(max=200) on `q` would never fire.
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        MethodValidationPostProcessor mvpp = new MethodValidationPostProcessor();
        mvpp.setValidator(validator);
        mvpp.afterPropertiesSet();
        Object proxied = mvpp.postProcessAfterInitialization(controller, "recipeController");

        mockMvc = MockMvcBuilders.standaloneSetup(proxied)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        Jwt token = mock(Jwt.class);
        when(token.getClaims()).thenReturn(Map.of("sub", USER_ID));
        // Standalone MockMvc has no security filter chain, so the controller's Authentication
        // argument is resolved from the request's userPrincipal. Set it directly.
        principal = new JwtAuthenticationToken(token);
    }

    @Test
    void getRecipes_rejectsOverlongQueryWith400() throws Exception {
        String overlong = "a".repeat(201);
        mockMvc.perform(get("/api/recipes").param("q", overlong).principal(principal))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getRecipes_acceptsQueryAtMaxLength() throws Exception {
        String atMax = "a".repeat(200);
        mockMvc.perform(get("/api/recipes").param("q", atMax).principal(principal))
                .andExpect(status().isOk());
    }
}
