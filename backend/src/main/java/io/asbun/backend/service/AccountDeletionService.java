package io.asbun.backend.service;

import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.Consent;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AccountStatus;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.ConsentRepository;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final RecipeRepository recipeRepository;
    private final ConsentRepository consentRepository;
    private final S3Service s3Service;
    private final AuditService auditService;
    private final CognitoIdentityProviderClient cognitoClient;

    @Value("${cognito.user-pool-id}")
    private String userPoolId;

    /**
     * Marks an account for soft deletion with a 30-day grace period.
     */
    public void requestSoftDeletion(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        user.setAccountStatus(AccountStatus.PENDING_DELETION);
        user.setDeletionRequestedAt(Instant.now());
        user.setScheduledDeletionDate(Instant.now().plus(30, ChronoUnit.DAYS));
        userRepository.save(user);

        auditService.logEvent(userId, AuditEventType.ACCOUNT_DELETION_REQUESTED,
                Map.of("type", "soft", "scheduledDeletionDate", user.getScheduledDeletionDate().toString()),
                null, null);

        log.info("Soft deletion requested for user: {}, scheduled for: {}", userId, user.getScheduledDeletionDate());
    }

    /**
     * Cancels a pending deletion if the grace period has not expired.
     */
    public void cancelDeletion(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (user.getAccountStatus() != AccountStatus.PENDING_DELETION) {
            throw new IllegalStateException("Account is not in PENDING_DELETION status");
        }

        if (user.getScheduledDeletionDate() != null && !user.getScheduledDeletionDate().isAfter(Instant.now())) {
            throw new IllegalStateException("Grace period has expired; deletion can no longer be cancelled");
        }

        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setDeletionRequestedAt(null);
        user.setScheduledDeletionDate(null);
        userRepository.save(user);

        auditService.logEvent(userId, AuditEventType.ACCOUNT_REACTIVATED,
                Map.of("action", "cancellation"),
                null, null);

        log.info("Deletion cancelled and account reactivated for user: {}", userId);
    }

    /**
     * Permanently deletes all user data from all stores.
     * Logs ACCOUNT_DELETION_COMPLETED only after all steps succeed.
     * On partial failure, marks the account as DELETION_FAILED and logs a failure event.
     */
    public void executeHardDeletion(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        try {
            // Step 1: Delete all recipes and their S3 images
            List<Recipe> recipes = recipeRepository.findByUserId(userId);
            for (Recipe recipe : recipes) {
                // Step 2: Delete S3 images for recipes that have them
                if (recipe.getImageUrl() != null) {
                    try {
                        s3Service.deleteImage(recipe.getImageUrl());
                    } catch (Exception e) {
                        log.error("Failed to delete S3 image for recipe {}: {}", recipe.getRecipeId(), e.getMessage());
                        markDeletionFailed(user, "s3_image_deletion", recipe.getRecipeId(), e);
                        throw new RuntimeException("Hard deletion failed at S3 image deletion step", e);
                    }
                }
                try {
                    recipeRepository.delete(recipe.getRecipeId());
                } catch (Exception e) {
                    log.error("Failed to delete recipe {}: {}", recipe.getRecipeId(), e.getMessage());
                    markDeletionFailed(user, "recipe_deletion", recipe.getRecipeId(), e);
                    throw new RuntimeException("Hard deletion failed at recipe deletion step", e);
                }
            }

            // Step 3: Delete all consent records
            try {
                List<Consent> consents = consentRepository.findAllByUserId(userId);
                for (Consent consent : consents) {
                    consentRepository.delete(userId, consent.getConsentType());
                }
            } catch (Exception e) {
                log.error("Failed to delete consent records for {}: {}", userId, e.getMessage());
                markDeletionFailed(user, "consent_deletion", userId, e);
                throw new RuntimeException("Hard deletion failed at consent deletion step", e);
            }

            // Step 4: Delete data export ZIP from S3
            try {
                String exportKey = "exports/" + userId + "/export.zip";
                s3Service.deleteImage(exportKey);
            } catch (Exception e) {
                log.warn("Failed to delete export ZIP for {} (may not exist): {}", userId, e.getMessage());
                // Export may not exist if user never requested one; continue deletion
            }

            // Step 5: Delete user from Cognito (before removing user record so retry is possible)
            try {
                cognitoClient.adminDeleteUser(AdminDeleteUserRequest.builder()
                        .userPoolId(userPoolId)
                        .username(user.getUsername())
                        .build());
            } catch (software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException e) {
                log.info("Cognito user already deleted for {} (idempotent)", userId);
            } catch (Exception e) {
                log.error("Failed to delete Cognito user for {}: {}", userId, e.getMessage());
                markDeletionFailed(user, "cognito_deletion", userId, e);
                throw new RuntimeException("Hard deletion failed at Cognito deletion step", e);
            }

            // Step 6: Delete user record from DynamoDB (last step — all external data is gone)
            try {
                userRepository.delete(userId);
            } catch (Exception e) {
                log.error("Failed to delete user record for {}: {}", userId, e.getMessage());
                markDeletionFailed(user, "user_record_deletion", userId, e);
                throw new RuntimeException("Hard deletion failed at user record deletion step", e);
            }

            // All steps succeeded — log completion
            auditService.logEvent(userId, AuditEventType.ACCOUNT_DELETION_COMPLETED,
                    Map.of("type", "hard", "userId", userId),
                    null, null);

            log.info("Hard deletion completed successfully for user: {}", userId);

        } catch (RuntimeException e) {
            log.error("Hard deletion failed for user {}: {}", userId, e.getMessage());
            throw e;
        }
    }

    /**
     * Scheduled job that processes overdue pending deletions and failed deletions daily.
     */
    @Scheduled(cron = "${compliance.deletion.cron:0 0 2 * * *}")
    public void processPendingDeletions() {
        log.info("Starting scheduled deletion processing");
        Instant now = Instant.now();

        List<User> candidates = userRepository.findPendingDeletions();

        int processed = 0;
        int succeeded = 0;
        int failed = 0;

        for (User user : candidates) {
            // Process PENDING_DELETION users only if their scheduled date has passed
            if (user.getAccountStatus() == AccountStatus.PENDING_DELETION
                    && user.getScheduledDeletionDate() != null
                    && user.getScheduledDeletionDate().isAfter(now)) {
                // Not yet due for deletion, skip
                continue;
            }

            processed++;
            try {
                executeHardDeletion(user.getUserId());
                succeeded++;
            } catch (Exception e) {
                failed++;
                log.error("Scheduled deletion failed for user {}: {}", user.getUserId(), e.getMessage());

                auditService.logEvent(user.getUserId(), AuditEventType.ACCOUNT_DELETION_FAILED,
                        Map.of("trigger", "scheduled", "error", e.getMessage() != null ? e.getMessage() : "Unknown error"),
                        null, null);
            }
        }

        // Log summary audit event
        auditService.logEvent("SYSTEM", AuditEventType.SCHEDULED_DELETION_RUN,
                Map.of(
                        "totalProcessed", String.valueOf(processed),
                        "succeeded", String.valueOf(succeeded),
                        "failed", String.valueOf(failed)
                ),
                null, null);

        log.info("Scheduled deletion processing completed: processed={}, succeeded={}, failed={}",
                processed, succeeded, failed);
    }

    private void markDeletionFailed(User user, String failedStep, String resourceId, Exception e) {
        user.setAccountStatus(AccountStatus.DELETION_FAILED);
        userRepository.save(user);

        auditService.logEvent(user.getUserId(), AuditEventType.ACCOUNT_DELETION_FAILED,
                Map.of("failedStep", failedStep, "resourceId", resourceId, "error", e.getMessage()),
                null, null);

        log.error("Marked user {} as DELETION_FAILED. Failed step: {}, resource: {}, error: {}",
                user.getUserId(), failedStep, resourceId, e.getMessage());
    }
}
