package io.asbun.backend.controller;

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
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RecipeController.getRecipes pagination/search wiring: page/pageSize clamping,
 * default page size, and user-scoping of the query.
 *
 * Feature: saved-recipe-search
 * Validates: Requirements 4.1, 4.3, 4.4
 */
class RecipeControllerPaginationTest {

    private static final String USER_ID = "user-123";

    private SavedRecipeSearchService savedRecipeSearchService;
    private RecipeController controller;
    private JwtAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        savedRecipeSearchService = mock(SavedRecipeSearchService.class);
        controller = new RecipeController(
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

        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", USER_ID));
        auth = new JwtAuthenticationToken(jwt);

        when(savedRecipeSearchService.search(any(SavedRecipeSearchQuery.class)))
                .thenReturn(new SavedRecipeSearchResults(List.of(), 0, 6, 0));
    }

    private SavedRecipeSearchQuery captureQuery() {
        ArgumentCaptor<SavedRecipeSearchQuery> captor =
                ArgumentCaptor.forClass(SavedRecipeSearchQuery.class);
        verify(savedRecipeSearchService).search(captor.capture());
        return captor.getValue();
    }

    @Test
    void usesDefaultPageSizeWhenAbsent() {
        controller.getRecipes(null, 0, null, auth);
        assertThat(captureQuery().pageSize()).isEqualTo(6);
    }

    @Test
    void capsPageSizeAtMax() {
        controller.getRecipes(null, 0, 500, auth);
        assertThat(captureQuery().pageSize()).isEqualTo(50);
    }

    @Test
    void clampsPageSizeToAtLeastOne() {
        controller.getRecipes(null, 0, 0, auth);
        assertThat(captureQuery().pageSize()).isEqualTo(1);
    }

    @Test
    void negativePageClampedToZero() {
        controller.getRecipes(null, -5, null, auth);
        assertThat(captureQuery().page()).isZero();
    }

    @Test
    void scopesQueryToAuthenticatedUserAndPassesText() {
        controller.getRecipes("chicken", 2, 6, auth);
        SavedRecipeSearchQuery query = captureQuery();
        assertThat(query.userId()).isEqualTo(USER_ID);
        assertThat(query.text()).isEqualTo("chicken");
        assertThat(query.page()).isEqualTo(2);
    }

    @Test
    void returnsSearchResultsBody() {
        SavedRecipeSearchResults expected = new SavedRecipeSearchResults(List.of(), 0, 6, 0);
        when(savedRecipeSearchService.search(any(SavedRecipeSearchQuery.class))).thenReturn(expected);

        assertThat(controller.getRecipes(null, 0, null, auth).getBody()).isSameAs(expected);
    }
}
