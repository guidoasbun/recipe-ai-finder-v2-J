package io.asbun.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a write is attempted on an account that is PENDING_DELETION or DELETION_FAILED.
 * Maps to 403, mirroring the forbidden response {@code RecipeController.generate} returns for
 * that account state.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AccountPendingDeletionException extends RuntimeException {
    public AccountPendingDeletionException(String message) {
        super(message);
    }
}
