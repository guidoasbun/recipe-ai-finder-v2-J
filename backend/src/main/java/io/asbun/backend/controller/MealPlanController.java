package io.asbun.backend.controller;

import io.asbun.backend.dto.AddEntryRequest;
import io.asbun.backend.dto.CreateMealPlanRequest;
import io.asbun.backend.dto.MealPlanDto;
import io.asbun.backend.dto.UpdateEntryRequest;
import io.asbun.backend.dto.UpdateMealPlanRequest;
import io.asbun.backend.service.MealPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Meal-plan CRUD + entry mutations. Authenticated by the existing {@code SecurityConfig}
 * (everything but health/privacy/terms). {@code userId} is the JWT {@code sub} claim; the
 * service scopes every operation to plans the caller owns and reports not-owned as 404.
 */
@Validated
@RestController
@RequestMapping("/api/meal-plans")
@RequiredArgsConstructor
public class MealPlanController {

    private static final String ID_PATTERN = "^[a-zA-Z0-9\\-]{1,36}$";

    private final MealPlanService mealPlanService;

    @PostMapping
    public ResponseEntity<MealPlanDto> create(
            @Valid @RequestBody CreateMealPlanRequest request,
            Authentication authentication) {
        MealPlanDto dto = mealPlanService.createPlan(request, getUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public ResponseEntity<List<MealPlanDto>> list(Authentication authentication) {
        return ResponseEntity.ok(mealPlanService.listPlans(getUserId(authentication)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<MealPlanDto> get(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            Authentication authentication) {
        return ResponseEntity.ok(mealPlanService.getPlan(id, getUserId(authentication)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<MealPlanDto> update(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            @Valid @RequestBody UpdateMealPlanRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(mealPlanService.updatePlan(id, request, getUserId(authentication)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            Authentication authentication) {
        mealPlanService.deletePlan(id, getUserId(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/entries")
    public ResponseEntity<MealPlanDto> addEntry(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            @Valid @RequestBody AddEntryRequest request,
            Authentication authentication) {
        MealPlanDto dto = mealPlanService.addEntry(id, request, getUserId(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @PutMapping("/{id}/entries/{entryId}")
    public ResponseEntity<MealPlanDto> updateEntry(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            @PathVariable @Pattern(regexp = ID_PATTERN) String entryId,
            @Valid @RequestBody UpdateEntryRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                mealPlanService.updateEntry(id, entryId, request, getUserId(authentication)));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    public ResponseEntity<MealPlanDto> removeEntry(
            @PathVariable @Pattern(regexp = ID_PATTERN) String id,
            @PathVariable @Pattern(regexp = ID_PATTERN) String entryId,
            Authentication authentication) {
        return ResponseEntity.ok(
                mealPlanService.removeEntry(id, entryId, getUserId(authentication)));
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }
}
