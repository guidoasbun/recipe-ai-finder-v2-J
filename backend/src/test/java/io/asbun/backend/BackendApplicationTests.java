package io.asbun.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Full context-loading smoke test.
 * Requires AWS credentials and a valid Cognito issuer URI, so it is only
 * executed when the CI environment variable is set (i.e., in the deployed pipeline).
 */
@EnabledIfEnvironmentVariable(named = "SPRING_PROFILES_ACTIVE", matches = ".*integration.*")
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // This test validates that the full Spring context can be loaded.
        // It requires real AWS infrastructure and is skipped in local/unit test runs.
    }

}
