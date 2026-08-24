package io.asbun.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.model.AuditEvent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 100;

    /**
     * Dedicated logger for structured audit records. Emits the complete event as JSON
     * so CloudWatch (or any log aggregator) can query all fields identically to DynamoDB.
     */
    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    public AuditEvent logEvent(String userId, AuditEventType eventType,
                               Map<String, String> details, String ipAddress, String userAgent) {
        AuditEvent event = AuditEvent.builder()
                .auditId(UUID.randomUUID().toString())
                .userId(userId)
                .eventType(eventType.name())
                .details(details)
                .timestamp(Instant.now().toString())
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .ttl(Instant.now().plus(90, ChronoUnit.DAYS).getEpochSecond())
                .build();

        saveWithRetry(event);

        // Emit the complete event as structured JSON for CloudWatch queryability
        emitStructuredAuditLog(event);

        return event;
    }

    /**
     * Serializes the full audit event to JSON and emits it through the dedicated AUDIT logger.
     * Falls back to field-level logging if serialization fails.
     */
    private void emitStructuredAuditLog(AuditEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            AUDIT_LOG.info(json);
        } catch (JsonProcessingException e) {
            // Fallback: log all fields individually so no data is lost
            AUDIT_LOG.info("auditId={} userId={} eventType={} timestamp={} ipAddress={} userAgent={} details={}",
                    event.getAuditId(), event.getUserId(), event.getEventType(),
                    event.getTimestamp(), event.getIpAddress(), event.getUserAgent(), event.getDetails());
            log.warn("Failed to serialize audit event to JSON: {}", e.getMessage());
        }
    }

    /**
     * Attempts to persist the audit event up to MAX_RETRIES times with exponential backoff.
     * If all attempts are exhausted, logs the full event at ERROR level so it is not silently lost,
     * then propagates the exception.
     */
    private void saveWithRetry(AuditEvent event) {
        int attempt = 0;
        while (true) {
            try {
                auditRepository.save(event);
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt >= MAX_RETRIES) {
                    log.error("Audit write failed after {} attempts. Event lost: auditId={}, userId={}, eventType={}, details={}",
                            MAX_RETRIES, event.getAuditId(), event.getUserId(), event.getEventType(), event.getDetails(), e);
                    throw new RuntimeException("Failed to persist audit event after " + MAX_RETRIES + " attempts", e);
                }
                long backoffMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // exponential: 100ms, 200ms
                log.warn("Audit write attempt {}/{} failed, retrying in {}ms: {}", attempt, MAX_RETRIES, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.error("Audit retry interrupted. Event: auditId={}, userId={}, eventType={}",
                            event.getAuditId(), event.getUserId(), event.getEventType());
                    throw new RuntimeException("Audit write interrupted", ie);
                }
            }
        }
    }
}
