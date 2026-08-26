package io.asbun.backend.controller;

import io.asbun.backend.dto.UpdateDietaryRestrictionsRequest;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.DietaryRestriction;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.AccountDeletionService;
import io.asbun.backend.service.DataExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AccountController dietary restriction endpoints.
 *
 * Feature: dietary-restrictions
 * Validates: Requirements 2.1, 2.2, 2.3, 2.5, 2.6
 */
class AccountControllerDietaryRestrictionsTest {

    private static final String USER_ID = "user-123";

    private UserRepository userRepository;
    private AccountController controller;
    private JwtAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        AccountDeletionService accountDeletionService = mock(AccountDeletionService.class);
        DataExportService dataExportService = mock(DataExportService.class);
        controller = new AccountController(accountDeletionService, dataExportService, userRepository);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", USER_ID, "email", "user@example.com"));
        auth = new JwtAuthenticationToken(jwt);
    }

    @Test
    void getDietaryRestrictions_returnsCurrentRestrictions() {
        List<String> current = List.of(DietaryRestriction.VEGAN.name(), DietaryRestriction.KETO.name());
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>(current)).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseEntity<List<String>> response = controller.getDietaryRestrictions(auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrderElementsOf(current);
    }

    @Test
    void getDietaryRestrictions_returnsEmptyListWhenNull() {
        User user = User.builder().userId(USER_ID).dietaryRestrictions(null).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        ResponseEntity<List<String>> response = controller.getDietaryRestrictions(auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getDietaryRestrictions_returns404WhenUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getDietaryRestrictions(auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateDietaryRestrictions_withValidValues_savesAndReturnsUpdatedList() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> requested = List.of(
                DietaryRestriction.VEGAN.name(), DietaryRestriction.GLUTEN_FREE.name());

        ResponseEntity<List<String>> response =
                controller.updateDietaryRestrictions(request(requested), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrderElementsOf(requested);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDietaryRestrictions())
                .containsExactlyInAnyOrderElementsOf(requested);
    }

    @Test
    void updateDietaryRestrictions_withInvalidValues_returns400AndDoesNotSave() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        List<String> requested = List.of(DietaryRestriction.VEGAN.name(), "PIZZA_ONLY");

        assertThatThrownBy(() -> controller.updateDietaryRestrictions(request(requested), auth))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PIZZA_ONLY");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void updateDietaryRestrictions_withDuplicates_savesDeduplicatedList() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        List<String> requested = List.of(
                DietaryRestriction.VEGAN.name(),
                DietaryRestriction.VEGAN.name(),
                DietaryRestriction.KETO.name());

        ResponseEntity<List<String>> response =
                controller.updateDietaryRestrictions(request(requested), auth);

        assertThat(response.getBody())
                .containsExactlyInAnyOrder(DietaryRestriction.VEGAN.name(), DietaryRestriction.KETO.name());
    }

    @Test
    void updateDietaryRestrictions_withEmptyList_clearsRestrictions() {
        User user = User.builder().userId(USER_ID)
                .dietaryRestrictions(new ArrayList<>(List.of(DietaryRestriction.VEGAN.name())))
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<List<String>> response =
                controller.updateDietaryRestrictions(request(new ArrayList<>()), auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDietaryRestrictions()).isEmpty();
    }

    @Test
    void updateDietaryRestrictions_returns404WhenUserNotFound() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        List<String> requested = List.of(DietaryRestriction.VEGAN.name());

        assertThatThrownBy(() -> controller.updateDietaryRestrictions(request(requested), auth))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never()).save(any(User.class));
    }

    private UpdateDietaryRestrictionsRequest request(List<String> restrictions) {
        UpdateDietaryRestrictionsRequest req = new UpdateDietaryRestrictionsRequest();
        req.setRestrictions(restrictions);
        return req;
    }
}
