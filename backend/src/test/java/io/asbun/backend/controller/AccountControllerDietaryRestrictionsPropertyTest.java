package io.asbun.backend.controller;

import io.asbun.backend.dto.UpdateDietaryRestrictionsRequest;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.DietaryRestriction;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.AccountDeletionService;
import io.asbun.backend.service.DataExportService;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for AccountController dietary restriction endpoints.
 *
 * Feature: dietary-restrictions
 * Validates: Requirements 1.1, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4, 2.5
 */
@Tag("Feature: dietary-restrictions")
class AccountControllerDietaryRestrictionsPropertyTest {

    private static final String[] VALID_VALUES = Arrays.stream(DietaryRestriction.values())
            .map(Enum::name)
            .toArray(String[]::new);

    // ========================================================================
    // Property 1: Restriction persistence round-trip
    // ========================================================================

    /**
     * Property 1: For any valid subset of predefined restrictions, saving via PUT and
     * retrieving via GET returns the same set (order-independent).
     *
     * Validates: Requirements 1.1, 2.1, 2.2, 2.5
     */
    @Property(tries = 100)
    @Tag("Property 1: Restriction persistence round-trip")
    void restrictions_roundTripThroughPutAndGet(
            @ForAll("userIds") String userId,
            @ForAll("validRestrictionSubsets") List<String> restrictions
    ) {
        UserRepository userRepository = mock(UserRepository.class);
        AccountController controller = newController(userRepository);

        // A single in-memory user whose state is mutated by save().
        User user = User.builder().userId(userId).dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtAuthenticationToken auth = createMockAuthentication(userId);

        controller.updateDietaryRestrictions(request(restrictions), auth);
        ResponseEntity<List<String>> getResponse = controller.getDietaryRestrictions(auth);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new HashSet<>(getResponse.getBody()))
                .isEqualTo(new HashSet<>(restrictions));
    }

    // ========================================================================
    // Property 2: Invalid restrictions are rejected without side effects
    // ========================================================================

    /**
     * Property 2: For any list containing at least one invalid value, PUT is rejected and
     * the stored restrictions remain unchanged (no save occurs).
     *
     * Validates: Requirements 1.3, 2.3
     */
    @Property(tries = 100)
    @Tag("Property 2: Invalid restrictions are rejected without side effects")
    void invalidRestrictions_rejectedWithoutSideEffects(
            @ForAll("listsWithInvalidValue") List<String> restrictions,
            @ForAll("userIds") String userId
    ) {
        UserRepository userRepository = mock(UserRepository.class);
        AccountController controller = newController(userRepository);

        List<String> existing = List.of(DietaryRestriction.VEGAN.name());
        User user = User.builder().userId(userId)
                .dietaryRestrictions(new ArrayList<>(existing)).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        JwtAuthenticationToken auth = createMockAuthentication(userId);

        Throwable thrown = catchThrowable(() ->
                controller.updateDietaryRestrictions(request(restrictions), auth));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        // No persistence side effect.
        verify(userRepository, never()).save(any(User.class));
        assertThat(user.getDietaryRestrictions()).isEqualTo(existing);
    }

    // ========================================================================
    // Property 3: Deduplication preserves unique values
    // ========================================================================

    /**
     * Property 3: For any valid list containing duplicates, PUT saves a deduplicated list
     * whose set equals the distinct input set.
     *
     * Validates: Requirements 2.4
     */
    @Property(tries = 100)
    @Tag("Property 3: Deduplication preserves unique values")
    void duplicates_areDeduplicatedOnSave(
            @ForAll("userIds") String userId,
            @ForAll("validRestrictionsWithDuplicates") List<String> restrictions
    ) {
        UserRepository userRepository = mock(UserRepository.class);
        AccountController controller = newController(userRepository);

        User user = User.builder().userId(userId).dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtAuthenticationToken auth = createMockAuthentication(userId);

        ResponseEntity<List<String>> response =
                controller.updateDietaryRestrictions(request(restrictions), auth);

        List<String> saved = response.getBody();
        Set<String> distinctInput = new HashSet<>(restrictions);
        assertThat(saved).doesNotHaveDuplicates();
        assertThat(new HashSet<>(saved)).isEqualTo(distinctInput);
    }

    // ========================================================================
    // Property 4: Maximum cardinality enforcement
    // ========================================================================

    /**
     * Property 4: For any submitted valid list, the stored list contains at most 10 elements.
     *
     * Validates: Requirements 1.4
     */
    @Property(tries = 100)
    @Tag("Property 4: Maximum cardinality enforcement")
    void storedRestrictions_neverExceedTen(
            @ForAll("userIds") String userId,
            @ForAll("validRestrictionSubsets") List<String> restrictions
    ) {
        UserRepository userRepository = mock(UserRepository.class);
        AccountController controller = newController(userRepository);

        User user = User.builder().userId(userId).dietaryRestrictions(new ArrayList<>()).build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        JwtAuthenticationToken auth = createMockAuthentication(userId);

        controller.updateDietaryRestrictions(request(restrictions), auth);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDietaryRestrictions().size()).isLessThanOrEqualTo(10);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(3).ofMaxLength(20);
    }

    /** Any subset (0..10, no duplicates) of the valid restriction values. */
    @Provide
    Arbitrary<List<String>> validRestrictionSubsets() {
        return Arbitraries.of(VALID_VALUES)
                .set()
                .ofMinSize(0)
                .ofMaxSize(VALID_VALUES.length)
                .map(ArrayList::new);
    }

    /** A valid list guaranteed to contain at least one duplicate. */
    @Provide
    Arbitrary<List<String>> validRestrictionsWithDuplicates() {
        Arbitrary<List<String>> base = Arbitraries.of(VALID_VALUES)
                .set().ofMinSize(1).ofMaxSize(VALID_VALUES.length).map(ArrayList::new);
        return base.map(unique -> {
            List<String> withDup = new ArrayList<>(unique);
            withDup.add(unique.get(0)); // force a duplicate
            return withDup;
        });
    }

    /** A list containing at least one value that is not a valid enum name. */
    @Provide
    Arbitrary<List<String>> listsWithInvalidValue() {
        Arbitrary<List<String>> validPart = Arbitraries.of(VALID_VALUES)
                .list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<String> invalid = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                .filter(s -> Arrays.stream(VALID_VALUES).noneMatch(v -> v.equals(s)));
        return Combinators.combine(validPart, invalid).as((valid, inv) -> {
            List<String> combined = new ArrayList<>(valid);
            combined.add(inv);
            return combined;
        });
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AccountController newController(UserRepository userRepository) {
        AccountDeletionService accountDeletionService = mock(AccountDeletionService.class);
        DataExportService dataExportService = mock(DataExportService.class);
        return new AccountController(accountDeletionService, dataExportService, userRepository);
    }

    private UpdateDietaryRestrictionsRequest request(List<String> restrictions) {
        UpdateDietaryRestrictionsRequest req = new UpdateDietaryRestrictionsRequest();
        req.setRestrictions(restrictions);
        return req;
    }

    private JwtAuthenticationToken createMockAuthentication(String userId) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", userId, "email", "user@example.com"));
        return new JwtAuthenticationToken(jwt);
    }
}
