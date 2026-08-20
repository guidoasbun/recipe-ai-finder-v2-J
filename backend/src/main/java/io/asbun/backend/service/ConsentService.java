package io.asbun.backend.service;

import io.asbun.backend.model.Consent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.repository.ConsentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRepository consentRepository;
    private final AuditService auditService;

    public Consent grantConsent(String userId, ConsentType type, String version, String ipAddress) {
        Optional<Consent> existing = consentRepository.findByUserIdAndType(userId, type.name());

        Consent consent;
        if (existing.isPresent()) {
            consent = existing.get();
            consent.setGranted(true);
            consent.setGrantedAt(Instant.now());
            consent.setVersion(version);
            consent.setIpAddress(ipAddress);
        } else {
            consent = Consent.builder()
                    .userId(userId)
                    .consentType(type.name())
                    .granted(true)
                    .grantedAt(Instant.now())
                    .version(version)
                    .ipAddress(ipAddress)
                    .build();
        }

        consentRepository.save(consent);

        auditService.logEvent(userId, AuditEventType.CONSENT_GRANTED,
                Map.of("consentType", type.name(), "version", version != null ? version : ""),
                ipAddress, null);

        return consent;
    }

    public Consent revokeConsent(String userId, ConsentType type, String ipAddress) {
        Consent consent = consentRepository.findByUserIdAndType(userId, type.name())
                .orElseThrow(() -> new IllegalStateException(
                        "No consent record found for user " + userId + " and type " + type.name()));

        consent.setGranted(false);
        consent.setRevokedAt(Instant.now());

        consentRepository.save(consent);

        auditService.logEvent(userId, AuditEventType.CONSENT_REVOKED,
                Map.of("consentType", type.name()),
                ipAddress, null);

        return consent;
    }

    public List<Consent> getConsents(String userId) {
        return consentRepository.findAllByUserId(userId);
    }

    public boolean hasActiveConsent(String userId, ConsentType type) {
        return consentRepository.findByUserIdAndType(userId, type.name())
                .map(Consent::getGranted)
                .orElse(false);
    }

    public boolean hasAllRequiredConsents(String userId) {
        return Arrays.stream(ConsentType.values())
                .allMatch(type -> hasActiveConsent(userId, type));
    }

    /**
     * Validates that the given string is a valid ConsentType.
     * @param consentTypeStr the string to validate
     * @return the corresponding ConsentType
     * @throws IllegalArgumentException if the string is not a valid consent type
     */
    public ConsentType validateConsentType(String consentTypeStr) {
        try {
            return ConsentType.valueOf(consentTypeStr);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Invalid consent type: " + consentTypeStr + ". Supported types are: " +
                            Arrays.toString(ConsentType.values()));
        }
    }
}
