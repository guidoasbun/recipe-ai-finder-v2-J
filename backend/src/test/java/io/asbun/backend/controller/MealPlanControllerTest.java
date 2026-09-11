package io.asbun.backend.controller;

import io.asbun.backend.dto.CreateMealPlanRequest;
import io.asbun.backend.dto.MealPlanDto;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.service.MealPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MealPlanController: userId is taken from the JWT sub claim, and the service's
 * non-disclosing not-found propagates for plans the caller does not own.
 */
class MealPlanControllerTest {

    private static final String USER_ID = "user-123";

    private MealPlanService service;
    private MealPlanController controller;
    private JwtAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        service = mock(MealPlanService.class);
        controller = new MealPlanController(service);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", USER_ID));
        auth = new JwtAuthenticationToken(jwt);
    }

    @Test
    void create_returns201WithPlanScopedToCaller() {
        CreateMealPlanRequest req = new CreateMealPlanRequest();
        req.setName("Week 1");
        when(service.createPlan(eq(req), eq(USER_ID)))
                .thenReturn(MealPlanDto.builder().mealPlanId("p1").ownerUserId(USER_ID).build());

        ResponseEntity<MealPlanDto> response = controller.create(req, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMealPlanId()).isEqualTo("p1");
    }

    @Test
    void list_returnsCallerPlans() {
        when(service.listPlans(USER_ID)).thenReturn(List.of(
                MealPlanDto.builder().mealPlanId("p1").ownerUserId(USER_ID).build()));

        ResponseEntity<List<MealPlanDto>> response = controller.list(auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void get_notOwned_propagatesNotFound() {
        when(service.getPlan("p1", USER_ID))
                .thenThrow(new ResourceNotFoundException("Meal plan not found: p1"));

        assertThatThrownBy(() -> controller.get("p1", auth))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_returns204() {
        ResponseEntity<Void> response = controller.delete("p1", auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
