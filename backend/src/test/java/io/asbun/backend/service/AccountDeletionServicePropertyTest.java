package io.asbun.backend.service;

import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.repository.UserRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for AccountDeletionService.
 * <p>
 * Validates: Requirements 1.1, 1.4, 1.5, 1.7, 2.1-2.9, 3.2, 3.3, 3.4
 */
@Tag("security-legal-compliance")
class AccountDeletionServicePropertyTest {

    private static final String USER_POOL_ID = "us-east-1_TestPool";

    // --- Providers ---

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(20);
    }

    @Provide
    Arbitrary<User> activeUsers() {
        return userIds().map(userId -> User.builder()
                .userId(userId)
                .email(userId + "@example.com")
                .username(userId)
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .generateCallsUsed(0)
                .accountStatus(AccountStatus.ACTIVE)
                .build());
    }

    @Provide
    Arbitrary<List<Recipe>> recipeLists() {
        Arbitrary<String> imageUrls = Arbitraries.oneOf(
                Arbitraries.just(null),
                Arbitraries.strings().withCharRange('a', 'z').ofLength(8)
                        .map(s -> "recipes/" + s + ".png")
        );

        Arbitrary<Recipe> recipes = Combinators.combine(
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                imageUrls
        ).as((title, imageUrl) -> Recipe.builder()
                .recipeId(UUID.randomUUID().toString())
                .title(title)
                .imageUrl(imageUrl)
                .build());

        return recipes.list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<User>> mixedUserLists() {
        // Generate a pool of unique userIds, then assign categories
        return Arbitraries.integers().between(1, 6).flatMap(totalCount -> {
            // Generate exactly totalCount unique user ids
            return Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(20)
                    .list().ofSize(totalCount).uniqueElements()
                    .flatMap(ids -> {
                        // For each id, randomly assign a category
                        Arbitrary<List<User>> users = Arbitraries.of("overdue", "future", "failed")
                                .list().ofSize(ids.size())
                                .map(categories -> {
                                    List<User> result = new ArrayList<>();
                                    for (int i = 0; i < ids.size(); i++) {
                                        String id = ids.get(i);
                                        String category = categories.get(i);
                                        User.UserBuilder builder = User.builder().userId(id);
                                        switch (category) {
                                            case "overdue":
                                                builder.accountStatus(AccountStatus.PENDING_DELETION)
                                                        .scheduledDeletionDate(Instant.now().minus(1, ChronoUnit.DAYS))
                                                        .deletionRequestedAt(Instant.now().minus(31, ChronoUnit.DAYS));
                                                break;
                                            case "future":
                                                builder.accountStatus(AccountStatus.PENDING_DELETION)
                                                        .scheduledDeletionDate(Instant.now().plus(15, ChronoUnit.DAYS))
                                                        .deletionRequestedAt(Instant.now().minus(15, ChronoUnit.DAYS));
                                                break;
                                            case "failed":
                                                builder.accountStatus(AccountStatus.DELETION_FAILED)
                                                        .scheduledDeletionDate(Instant.now().minus(5, ChronoUnit.DAYS))
                                                        .deletionRequestedAt(Instant.now().minus(35, ChronoUnit.DAYS));
                                                break;
                                        }
                                        result.add(builder.build());
                                    }
                                    return result;
                                });
                        return users;
                    });
        });
    }

    // --- Helper ---

    private AccountDeletionService createService(UserRepository userRepository,
                                                  RecipeRepository recipeRepository,
                                                  S3Service s3Service,
                                                  AuditService auditService,
                                                  CognitoIdentityProviderClient cognitoClient) {
        AccountDeletionService service = new AccountDeletionService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);
        ReflectionTestUtils.setField(service, "userPoolId", USER_POOL_ID);
        return service;
    }

    // --- Property 1: Soft deletion state transition correctness ---

    /**
     * Property 1: For any active user, requesting soft deletion SHALL:
     * a. Set accountStatus to PENDING_DELETION
     * b. Set deletionRequestedAt to approximately now
     * c. Set scheduledDeletionDate to approximately now + 30 days
     * d. Save the user via repository
     * e. Log ACCOUNT_DELETION_REQUESTED audit event
     *
     * Validates: Requirements 1.1, 1.4, 1.5
     */
    @Property(tries = 100)
    @Tag("soft-deletion-state-transition")
    void softDeletion_setsCorrectStateAndLogsAudit(@ForAll("activeUsers") User user) {
        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Service s3Service = mock(S3Service.class);
        AuditService auditService = mock(AuditService.class);
        CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountDeletionService service = createService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);

        Instant beforeCall = Instant.now();

        // Act
        service.requestSoftDeletion(user.getUserId());

        Instant afterCall = Instant.now();

        // Assert - user saved with correct status
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        User savedUser = userCaptor.getValue();

        // a. Account status is PENDING_DELETION
        assertThat(savedUser.getAccountStatus()).isEqualTo(AccountStatus.PENDING_DELETION);

        // b. deletionRequestedAt is approximately now
        assertThat(savedUser.getDeletionRequestedAt()).isNotNull();
        assertThat(savedUser.getDeletionRequestedAt()).isAfterOrEqualTo(beforeCall.truncatedTo(ChronoUnit.MILLIS));
        assertThat(savedUser.getDeletionRequestedAt()).isBeforeOrEqualTo(afterCall.plusMillis(1));

        // c. scheduledDeletionDate is approximately now + 30 days
        assertThat(savedUser.getScheduledDeletionDate()).isNotNull();
        Instant expectedMin = beforeCall.plus(30, ChronoUnit.DAYS).minusSeconds(5);
        Instant expectedMax = afterCall.plus(30, ChronoUnit.DAYS).plusSeconds(5);
        assertThat(savedUser.getScheduledDeletionDate()).isBetween(expectedMin, expectedMax);

        // d. User was saved (verified above via ArgumentCaptor)

        // e. ACCOUNT_DELETION_REQUESTED audit event logged
        verify(auditService, times(1)).logEvent(
                eq(user.getUserId()),
                eq(AuditEventType.ACCOUNT_DELETION_REQUESTED),
                any(),
                isNull(),
                isNull()
        );
    }

    // --- Property 3: Hard deletion completeness ---

    /**
     * Property 3: For any user with 0-5 recipes (some with images, some without),
     * executing a hard deletion SHALL:
     * a. Delete all recipes from RecipeRepository
     * b. Delete all S3 images for recipes with imageUrl
     * c. Delete user record from UserRepository
     * d. Call Cognito AdminDeleteUser
     * e. Log ACCOUNT_DELETION_COMPLETED audit BEFORE user deletion
     *
     * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 2.9
     */
    @Property(tries = 100)
    @Tag("hard-deletion-completeness")
    void hardDeletion_deletesAllResourcesAndLogsAudit(
            @ForAll("userIds") String userId,
            @ForAll("recipeLists") List<Recipe> recipes) {

        // Arrange
        User user = User.builder()
                .userId(userId)
                .accountStatus(AccountStatus.PENDING_DELETION)
                .build();

        // Set userId on all recipes
        recipes.forEach(r -> r.setUserId(userId));

        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Service s3Service = mock(S3Service.class);
        AuditService auditService = mock(AuditService.class);
        CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(recipes);
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        AccountDeletionService service = createService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);

        // Act
        service.executeHardDeletion(userId);

        // Assert

        // e. Audit logged BEFORE deletion (verified by InOrder)
        var inOrder = inOrder(auditService, recipeRepository, userRepository, cognitoClient);
        inOrder.verify(auditService).logEvent(
                eq(userId),
                eq(AuditEventType.ACCOUNT_DELETION_COMPLETED),
                any(),
                isNull(),
                isNull()
        );

        // a. All recipes deleted
        for (Recipe recipe : recipes) {
            inOrder.verify(recipeRepository).delete(recipe.getRecipeId());
        }

        // b. All S3 images deleted for recipes that have imageUrl
        List<Recipe> recipesWithImages = recipes.stream()
                .filter(r -> r.getImageUrl() != null)
                .collect(Collectors.toList());

        // Total S3 delete calls equals number of recipes with images
        verify(s3Service, times(recipesWithImages.size())).deleteImage(any());

        // Each distinct imageUrl is called the expected number of times
        Map<String, Long> imageUrlCounts = recipesWithImages.stream()
                .collect(Collectors.groupingBy(Recipe::getImageUrl, Collectors.counting()));
        for (Map.Entry<String, Long> entry : imageUrlCounts.entrySet()) {
            verify(s3Service, times(entry.getValue().intValue())).deleteImage(entry.getKey());
        }

        // c. User record deleted
        inOrder.verify(userRepository).delete(userId);

        // d. Cognito AdminDeleteUser called
        ArgumentCaptor<AdminDeleteUserRequest> cognitoCaptor =
                ArgumentCaptor.forClass(AdminDeleteUserRequest.class);
        inOrder.verify(cognitoClient).adminDeleteUser(cognitoCaptor.capture());
        AdminDeleteUserRequest cognitoRequest = cognitoCaptor.getValue();
        assertThat(cognitoRequest.userPoolId()).isEqualTo(USER_POOL_ID);
        assertThat(cognitoRequest.username()).isEqualTo(userId);
    }

    // --- Property 4: Partial deletion failure handling ---

    /**
     * Property 4: When S3 deletion or recipe deletion fails mid-way:
     * a. Account status is set to DELETION_FAILED
     * b. User is saved with DELETION_FAILED status
     * c. RuntimeException is thrown
     *
     * Validates: Requirements 2.7, 2.8
     */
    @Property(tries = 100)
    @Tag("partial-deletion-failure")
    void partialFailure_marksDeletionFailedAndThrows(@ForAll("userIds") String userId) {
        // Arrange - user with at least one recipe that has an image
        User user = User.builder()
                .userId(userId)
                .accountStatus(AccountStatus.PENDING_DELETION)
                .build();

        Recipe recipeWithImage = Recipe.builder()
                .recipeId(UUID.randomUUID().toString())
                .userId(userId)
                .title("Test Recipe")
                .imageUrl("recipes/test-image.png")
                .build();

        List<Recipe> recipes = List.of(recipeWithImage);

        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Service s3Service = mock(S3Service.class);
        AuditService auditService = mock(AuditService.class);
        CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(recipes);

        // Simulate S3 failure
        doThrow(new RuntimeException("S3 connection timeout"))
                .when(s3Service).deleteImage(any());

        AccountDeletionService service = createService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);

        // Act & Assert - RuntimeException is thrown
        assertThatThrownBy(() -> service.executeHardDeletion(userId))
                .isInstanceOf(RuntimeException.class);

        // Assert - user saved with DELETION_FAILED status
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());

        // Find the save call that sets DELETION_FAILED
        List<User> savedUsers = userCaptor.getAllValues();
        boolean foundDeletionFailed = savedUsers.stream()
                .anyMatch(u -> u.getAccountStatus() == AccountStatus.DELETION_FAILED);
        assertThat(foundDeletionFailed).isTrue();
    }

    /**
     * Property 4 (variant): When recipe deletion fails mid-way:
     * a. Account status is set to DELETION_FAILED
     * b. User is saved with DELETION_FAILED status
     * c. RuntimeException is thrown
     *
     * Validates: Requirements 2.7, 2.8
     */
    @Property(tries = 100)
    @Tag("partial-deletion-failure")
    void partialFailure_recipeDeletion_marksDeletionFailedAndThrows(@ForAll("userIds") String userId) {
        // Arrange - user with recipe where recipe deletion fails
        User user = User.builder()
                .userId(userId)
                .accountStatus(AccountStatus.PENDING_DELETION)
                .build();

        Recipe recipe = Recipe.builder()
                .recipeId(UUID.randomUUID().toString())
                .userId(userId)
                .title("Test Recipe")
                .imageUrl(null) // no image, so S3 step is skipped
                .build();

        List<Recipe> recipes = List.of(recipe);

        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Service s3Service = mock(S3Service.class);
        AuditService auditService = mock(AuditService.class);
        CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(recipes);

        // Simulate recipe deletion failure
        doThrow(new RuntimeException("DynamoDB write capacity exceeded"))
                .when(recipeRepository).delete(any());

        AccountDeletionService service = createService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);

        // Act & Assert - RuntimeException is thrown
        assertThatThrownBy(() -> service.executeHardDeletion(userId))
                .isInstanceOf(RuntimeException.class);

        // Assert - user saved with DELETION_FAILED status
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, atLeastOnce()).save(userCaptor.capture());

        List<User> savedUsers = userCaptor.getAllValues();
        boolean foundDeletionFailed = savedUsers.stream()
                .anyMatch(u -> u.getAccountStatus() == AccountStatus.DELETION_FAILED);
        assertThat(foundDeletionFailed).isTrue();
    }

    // --- Property 5: Scheduled deletion job filtering and idempotence ---

    /**
     * Property 5: For a mix of users (some PENDING_DELETION with overdue dates,
     * some with future dates, some DELETION_FAILED):
     * a. Only overdue PENDING_DELETION and DELETION_FAILED users are processed
     * b. Users with future scheduledDeletionDate are NOT processed
     * c. SCHEDULED_DELETION_RUN summary audit event logged with correct counts
     *
     * Validates: Requirements 1.7, 3.2, 3.3, 3.4
     */
    @Property(tries = 100)
    @Tag("scheduled-deletion-filtering")
    void scheduledJob_filtersCorrectlyAndLogsSummary(@ForAll("mixedUserLists") List<User> allUsers) {
        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Service s3Service = mock(S3Service.class);
        AuditService auditService = mock(AuditService.class);
        CognitoIdentityProviderClient cognitoClient = mock(CognitoIdentityProviderClient.class);

        // findPendingDeletions returns all users with PENDING_DELETION or DELETION_FAILED status
        when(userRepository.findPendingDeletions()).thenReturn(allUsers);

        // For each user that should be processed, mock findById
        for (User user : allUsers) {
            when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        }
        // No recipes for any user (simplifies this test which focuses on filtering)
        when(recipeRepository.findByUserId(any())).thenReturn(Collections.emptyList());
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        AccountDeletionService service = createService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);

        // Identify which users should be processed
        List<User> overdueUsers = allUsers.stream()
                .filter(u -> u.getAccountStatus() == AccountStatus.PENDING_DELETION
                        && u.getScheduledDeletionDate() != null
                        && !u.getScheduledDeletionDate().isAfter(Instant.now()))
                .collect(Collectors.toList());

        List<User> failedUsers = allUsers.stream()
                .filter(u -> u.getAccountStatus() == AccountStatus.DELETION_FAILED)
                .collect(Collectors.toList());

        List<User> futureUsers = allUsers.stream()
                .filter(u -> u.getAccountStatus() == AccountStatus.PENDING_DELETION
                        && u.getScheduledDeletionDate() != null
                        && u.getScheduledDeletionDate().isAfter(Instant.now()))
                .collect(Collectors.toList());

        int expectedProcessed = overdueUsers.size() + failedUsers.size();

        // Act
        service.processPendingDeletions();

        // Assert - b. Future users are NOT processed (no executeHardDeletion calls for them)
        for (User futureUser : futureUsers) {
            // executeHardDeletion calls userRepository.findById internally,
            // but for future users it should not be reached via executeHardDeletion path
            // We verify no deletion audit event is logged for them
            verify(auditService, never()).logEvent(
                    eq(futureUser.getUserId()),
                    eq(AuditEventType.ACCOUNT_DELETION_COMPLETED),
                    any(),
                    any(),
                    any()
            );
        }

        // Assert - a. Overdue and failed users ARE processed
        for (User overdueUser : overdueUsers) {
            verify(auditService).logEvent(
                    eq(overdueUser.getUserId()),
                    eq(AuditEventType.ACCOUNT_DELETION_COMPLETED),
                    any(),
                    isNull(),
                    isNull()
            );
        }
        for (User failedUser : failedUsers) {
            verify(auditService).logEvent(
                    eq(failedUser.getUserId()),
                    eq(AuditEventType.ACCOUNT_DELETION_COMPLETED),
                    any(),
                    isNull(),
                    isNull()
            );
        }

        // Assert - c. SCHEDULED_DELETION_RUN summary audit event logged
        ArgumentCaptor<Map<String, String>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logEvent(
                eq("SYSTEM"),
                eq(AuditEventType.SCHEDULED_DELETION_RUN),
                detailsCaptor.capture(),
                isNull(),
                isNull()
        );

        Map<String, String> summaryDetails = detailsCaptor.getValue();
        assertThat(summaryDetails).containsKey("totalProcessed");
        assertThat(summaryDetails).containsKey("succeeded");
        assertThat(summaryDetails).containsKey("failed");

        int totalProcessed = Integer.parseInt(summaryDetails.get("totalProcessed"));
        int succeeded = Integer.parseInt(summaryDetails.get("succeeded"));
        int failed = Integer.parseInt(summaryDetails.get("failed"));

        assertThat(totalProcessed).isEqualTo(expectedProcessed);
        assertThat(succeeded + failed).isEqualTo(totalProcessed);
        // Since mocks don't throw, all should succeed
        assertThat(succeeded).isEqualTo(expectedProcessed);
        assertThat(failed).isEqualTo(0);
    }
}
