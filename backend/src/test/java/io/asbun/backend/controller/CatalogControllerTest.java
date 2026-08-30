package io.asbun.backend.controller;

import io.asbun.backend.dto.CatalogRecipeDto;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.DietaryRestriction;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.search.CatalogSearchQuery;
import io.asbun.backend.search.CatalogSearchResults;
import io.asbun.backend.search.CatalogSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for CatalogController: effective dietary-tag resolution, pagination bounds,
 * and 404 handling.
 *
 * Feature: existing-recipe-search
 * Validates: Requirements 1.4, 2.4, 4.1, 4.3, 5.1, 5.3
 */
class CatalogControllerTest {

    private static final String USER_ID = "user-123";

    private CatalogSearchService searchService;
    private UserRepository userRepository;
    private CatalogController controller;
    private JwtAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        searchService = mock(CatalogSearchService.class);
        userRepository = mock(UserRepository.class);
        controller = new CatalogController(searchService, userRepository);
        ReflectionTestUtils.setField(controller, "defaultPageSize", 20);
        ReflectionTestUtils.setField(controller, "maxPageSize", 50);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", USER_ID));
        auth = new JwtAuthenticationToken(jwt);

        when(searchService.search(any(CatalogSearchQuery.class)))
                .thenReturn(new CatalogSearchResults(List.of(), 0, 20, 0));
    }

    @Test
    void search_usesUsersSavedRestrictionsWhenNoTagsProvided() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>(List.of(DietaryRestriction.VEGAN.name())))
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        controller.search("salad", null, 0, null, auth);

        ArgumentCaptor<CatalogSearchQuery> captor = ArgumentCaptor.forClass(CatalogSearchQuery.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().dietaryTags()).containsExactly(DietaryRestriction.VEGAN.name());
    }

    @Test
    void search_requestTagsOverrideSavedRestrictions() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>(List.of(DietaryRestriction.VEGAN.name())))
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        controller.search("salad", List.of(DietaryRestriction.KETO.name()), 0, null, auth);

        ArgumentCaptor<CatalogSearchQuery> captor = ArgumentCaptor.forClass(CatalogSearchQuery.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        // Request override wins; the user's saved VEGAN is not applied.
        assertThat(captor.getValue().dietaryTags()).containsExactly(DietaryRestriction.KETO.name());
    }

    @Test
    void search_dropsInvalidRequestTags() {
        controller.search("salad", List.of("VEGAN", "PIZZA_ONLY"), 0, null, auth);

        ArgumentCaptor<CatalogSearchQuery> captor = ArgumentCaptor.forClass(CatalogSearchQuery.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().dietaryTags()).containsExactly("VEGAN");
    }

    @Test
    void search_capsPageSizeAtMax() {
        controller.search(null, List.of(), 0, 500, auth);

        ArgumentCaptor<CatalogSearchQuery> captor = ArgumentCaptor.forClass(CatalogSearchQuery.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().pageSize()).isEqualTo(50);
    }

    @Test
    void search_negativePageClampedToZero() {
        controller.search(null, List.of(), -5, null, auth);

        ArgumentCaptor<CatalogSearchQuery> captor = ArgumentCaptor.forClass(CatalogSearchQuery.class);
        org.mockito.Mockito.verify(searchService).search(captor.capture());
        assertThat(captor.getValue().page()).isZero();
    }

    @Test
    void getById_returnsRecipeWhenFound() {
        CatalogRecipeDto dto = CatalogRecipeDto.builder()
                .catalogRecipeId("abc123").title("Test Recipe").build();
        when(searchService.findById("abc123")).thenReturn(Optional.of(dto));

        ResponseEntity<CatalogRecipeDto> response = controller.getById("abc123", auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Test Recipe");
    }

    @Test
    void getById_throwsNotFoundWhenMissing() {
        when(searchService.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getById("missing", auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
