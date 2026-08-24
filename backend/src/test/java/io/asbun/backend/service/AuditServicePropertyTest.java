package io.asbun.backend.service;

import io.asbun.backend.model.AuditEvent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.AuditRepository;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for AuditService.
 * <p>
 * Validates: Requirements 8.1, 8.2, 8.5
 */
@Tag("security-legal-compliance")
@Tag("audit-dual-write-completeness")
class AuditServicePropertyTest {

    // --- Providers ---

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(36);
    }

    @Provide
    Arbitrary<AuditEventType> eventTypes() {
        return Arbitraries.of(AuditEventType.values());
    }

    @Provide
    Arbitrary<Map<String, String>> detailsMaps() {
        Arbitrary<String> keys = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(64);
        Arbitrary<String> values = Arbitraries.strings().alpha().numeric().ofMinLength(0).ofMaxLength(1024);
        return Arbitraries.maps(keys, values).ofMinSize(0).ofMaxSize(10);
    }

    @Provide
    Arbitrary<String> ipAddresses() {
        Arbitrary<String> ipv4 = Arbitraries.integers().between(0, 255).list().ofSize(4)
                .map(octets -> octets.get(0) + "." + octets.get(1) + "." + octets.get(2) + "." + octets.get(3));
        Arbitrary<String> ipv6 = Arbitraries.strings().withCharRange('0', '9').withCharRange('a', 'f')
                .ofLength(4).list().ofSize(8)
                .map(groups -> String.join(":", groups));
        return Arbitraries.oneOf(ipv4, ipv6);
    }

    @Provide
    Arbitrary<String> userAgents() {
        return Arbitraries.of(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)",
                "Mozilla/5.0 (X11; Linux x86_64)",
                "PostmanRuntime/7.32.3",
                "curl/8.1.2",
                "RecipeAI-Mobile/2.1.0"
        );
    }

    // --- Property Tests ---

    /**
     * Property: For any random audit event, DynamoDB save is called exactly once
     * with the correct event data, and the returned AuditEvent has a valid UUID
     * for auditId, ISO-8601 timestamp, TTL ~90 days from now, and fields matching input.
     *
     * Validates: Requirements 8.1, 8.2, 8.5
     */
    @Property(tries = 100)
    void auditEvent_persistedToDynamoAndFieldsMatch(
            @ForAll("userIds") String userId,
            @ForAll("eventTypes") AuditEventType eventType,
            @ForAll("detailsMaps") Map<String, String> details,
            @ForAll("ipAddresses") String ipAddress,
            @ForAll("userAgents") String userAgent) {

        // Arrange
        AuditRepository mockRepository = mock(AuditRepository.class);
        when(mockRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        AuditService auditService = new AuditService(mockRepository, new com.fasterxml.jackson.databind.ObjectMapper());

        Instant beforeCall = Instant.now();

        // Act
        AuditEvent result = auditService.logEvent(userId, eventType, details, ipAddress, userAgent);

        Instant afterCall = Instant.now();

        // Assert - save called exactly once
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(mockRepository, times(1)).save(captor.capture());
        AuditEvent savedEvent = captor.getValue();

        // Assert - returned event matches saved event
        assertThat(result).isSameAs(savedEvent);

        // Assert - auditId is a valid UUID
        assertThat(result.getAuditId()).isNotNull();
        UUID.fromString(result.getAuditId()); // throws if invalid

        // Assert - fields match input
        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getEventType()).isEqualTo(eventType.name());
        assertThat(result.getDetails()).isEqualTo(details);
        assertThat(result.getIpAddress()).isEqualTo(ipAddress);
        assertThat(result.getUserAgent()).isEqualTo(userAgent);

        // Assert - timestamp is valid ISO-8601 and within the call window
        assertThat(result.getTimestamp()).isNotNull();
        Instant parsedTimestamp = Instant.parse(result.getTimestamp());
        assertThat(parsedTimestamp).isAfterOrEqualTo(beforeCall.truncatedTo(ChronoUnit.MILLIS));
        assertThat(parsedTimestamp).isBeforeOrEqualTo(afterCall.plusMillis(1));

        // Assert - TTL is approximately 90 days from now (within 5 seconds tolerance)
        assertThat(result.getTtl()).isNotNull();
        long expectedTtlMin = beforeCall.plus(90, ChronoUnit.DAYS).getEpochSecond() - 5;
        long expectedTtlMax = afterCall.plus(90, ChronoUnit.DAYS).getEpochSecond() + 5;
        assertThat(result.getTtl()).isBetween(expectedTtlMin, expectedTtlMax);
    }

    /**
     * Property: When DynamoDB save throws an exception, the exception propagates
     * (is not swallowed), ensuring the triggering operation does not complete
     * without an audit trail.
     *
     * Validates: Requirements 8.5
     */
    @Property(tries = 100)
    void auditEvent_exceptionPropagatesWhenSaveFails(
            @ForAll("userIds") String userId,
            @ForAll("eventTypes") AuditEventType eventType,
            @ForAll("detailsMaps") Map<String, String> details,
            @ForAll("ipAddresses") String ipAddress,
            @ForAll("userAgents") String userAgent) {

        // Arrange
        AuditRepository mockRepository = mock(AuditRepository.class);
        RuntimeException simulatedFailure = new RuntimeException("DynamoDB write failed after retries");
        when(mockRepository.save(any(AuditEvent.class))).thenThrow(simulatedFailure);

        AuditService auditService = new AuditService(mockRepository, new com.fasterxml.jackson.databind.ObjectMapper());

        // Act & Assert - exception propagates
        assertThatThrownBy(() -> auditService.logEvent(userId, eventType, details, ipAddress, userAgent))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to persist audit event after 3 attempts");

        // Verify save was attempted
        verify(mockRepository, times(1)).save(any(AuditEvent.class));
    }
}
