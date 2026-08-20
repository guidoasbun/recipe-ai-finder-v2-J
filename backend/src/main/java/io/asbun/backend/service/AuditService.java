package io.asbun.backend.service;

import io.asbun.backend.model.AuditEvent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.AuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository auditRepository;

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

        auditRepository.save(event);

        log.info("Audit event logged: userId={}, eventType={}, details={}",
                userId, eventType.name(), details);

        return event;
    }
}
