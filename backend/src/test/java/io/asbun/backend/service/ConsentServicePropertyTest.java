package io.asbun.backend.service;

import io.asbun.backend.model.Consent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.repository.ConsentRepository;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for ConsentService.
 *
 * Validates: Requirements 6.1, 6.2, 6.4, 6.5, 6.7, 6.8
 */
@Tag("Feature: security-legal-compliance")
class ConsentServicePropertyTest {

    private static final Set<String> VALID_CONSENT_TYPES = Arrays.stream(ConsentType.values())
            .map(Enum::name)
            .collect(Collectors.toSet());

    // ========================================================================
    // Property 9: Consent grant round-trip and idempotence
    // ========================================================================

    /**
     * Property 9a: Granting consent results in a record with granted=true,
     * correct userId/type/version/IP, non-null grantedAt.
     *
     * Validates: Requirements 6.1, 6.2
     */
    @Property(tries = 100)
    @Tag("Property 9: Consent grant round-trip and idempotence")
    void grantConsent_createsCorrectRecord(
            @ForAll("userIds") String userId,
            @ForAll("consentTypes") ConsentType type,
            @ForAll("versions") String version,
            @ForAll("ipAddresses") String ipAddress
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        when(consentRepository.findByUserIdAndType(userId, type.name()))
                .thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.grantConsent(userId, type, version, ipAddress);

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getConsentType()).isEqualTo(type.name());
        assertThat(result.getGranted()).isTrue();
        assertThat(result.getGrantedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(version);
        assertThat(result.getIpAddress()).isEqualTo(ipAddress);

        verify(consentRepository).save(any(Consent.class));
    }

    /**
     * Property 9b: Granting the same consent again (idempotence) updates the record
     * without creating a duplicate — verify save is called with same userId/consentType combo.
     *
     * Validates: Requirements 6.8
     */
    @Property(tries = 100)
    @Tag("Property 9: Consent grant round-trip and idempotence")
    void grantConsent_idempotent_updatesExistingRecord(
            @ForAll("userIds") String userId,
            @ForAll("consentTypes") ConsentType type,
            @ForAll("versions") String firstVersion,
            @ForAll("versions") String secondVersion,
            @ForAll("ipAddresses") String firstIp,
            @ForAll("ipAddresses") String secondIp
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        Instant originalGrantedAt = Instant.now().minusSeconds(3600);
        Consent existing = Consent.builder()
                .userId(userId)
                .consentType(type.name())
                .granted(true)
                .grantedAt(originalGrantedAt)
                .version(firstVersion)
                .ipAddress(firstIp)
                .build();

        when(consentRepository.findByUserIdAndType(userId, type.name()))
                .thenReturn(Optional.of(existing));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.grantConsent(userId, type, secondVersion, secondIp);

        // Updated fields
        assertThat(result.getGranted()).isTrue();
        assertThat(result.getVersion()).isEqualTo(secondVersion);
        assertThat(result.getIpAddress()).isEqualTo(secondIp);
        assertThat(result.getGrantedAt()).isNotNull();
        assertThat(result.getGrantedAt()).isNotEqualTo(originalGrantedAt);

        // Same record — save is called with existing object (same userId/consentType)
        ArgumentCaptor<Consent> captor = ArgumentCaptor.forClass(Consent.class);
        verify(consentRepository).save(captor.capture());
        Consent saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getConsentType()).isEqualTo(type.name());

        // Only one save call — no duplicate creation
        verify(consentRepository, times(1)).save(any(Consent.class));
    }

    /**
     * Property 9c: After revoking: granted=false, revokedAt is set,
     * grantedAt and version are preserved from original grant.
     *
     * Validates: Requirements 6.2
     */
    @Property(tries = 100)
    @Tag("Property 9: Consent grant round-trip and idempotence")
    void revokeConsent_preservesOriginalGrantFields(
            @ForAll("userIds") String userId,
            @ForAll("consentTypes") ConsentType type,
            @ForAll("versions") String version,
            @ForAll("ipAddresses") String grantIp,
            @ForAll("ipAddresses") String revokeIp
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        Instant originalGrantedAt = Instant.now().minusSeconds(7200);
        Consent existing = Consent.builder()
                .userId(userId)
                .consentType(type.name())
                .granted(true)
                .grantedAt(originalGrantedAt)
                .version(version)
                .ipAddress(grantIp)
                .build();

        when(consentRepository.findByUserIdAndType(userId, type.name()))
                .thenReturn(Optional.of(existing));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.revokeConsent(userId, type, revokeIp);

        assertThat(result.getGranted()).isFalse();
        assertThat(result.getRevokedAt()).isNotNull();
        // Original grant fields preserved
        assertThat(result.getGrantedAt()).isEqualTo(originalGrantedAt);
        assertThat(result.getVersion()).isEqualTo(version);

        verify(consentRepository).save(any(Consent.class));
        verify(auditService).logEvent(eq(userId), eq(AuditEventType.CONSENT_REVOKED),
                anyMap(), eq(revokeIp), isNull());
    }

    // ========================================================================
    // Property 10: Consent gates recipe generation
    // ========================================================================

    /**
     * Property 10a: hasActiveConsent returns true only when a record exists with granted=true.
     *
     * Validates: Requirements 6.4, 6.5
     */
    @Property(tries = 100)
    @Tag("Property 10: Consent gates recipe generation")
    void hasActiveConsent_trueOnlyWhenGrantedTrue(
            @ForAll("userIds") String userId,
            @ForAll("consentTypes") ConsentType type
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        Consent grantedConsent = Consent.builder()
                .userId(userId)
                .consentType(type.name())
                .granted(true)
                .build();

        when(consentRepository.findByUserIdAndType(userId, type.name()))
                .thenReturn(Optional.of(grantedConsent));

        assertThat(consentService.hasActiveConsent(userId, type)).isTrue();
    }

    /**
     * Property 10b: hasActiveConsent returns false when record doesn't exist or granted=false.
     *
     * Validates: Requirements 6.4, 6.5
     */
    @Property(tries = 100)
    @Tag("Property 10: Consent gates recipe generation")
    void hasActiveConsent_falseWhenNoRecordOrGrantedFalse(
            @ForAll("userIds") String userId,
            @ForAll("consentTypes") ConsentType type,
            @ForAll boolean recordExists
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        if (recordExists) {
            Consent revokedConsent = Consent.builder()
                    .userId(userId)
                    .consentType(type.name())
                    .granted(false)
                    .build();
            when(consentRepository.findByUserIdAndType(userId, type.name()))
                    .thenReturn(Optional.of(revokedConsent));
        } else {
            when(consentRepository.findByUserIdAndType(userId, type.name()))
                    .thenReturn(Optional.empty());
        }

        assertThat(consentService.hasActiveConsent(userId, type)).isFalse();
    }

    /**
     * Property 10c: hasAllRequiredConsents returns true only when ALL three types have granted=true.
     *
     * Validates: Requirements 6.4, 6.5
     */
    @Property(tries = 100)
    @Tag("Property 10: Consent gates recipe generation")
    void hasAllRequiredConsents_trueOnlyWhenAllGranted(
            @ForAll("userIds") String userId,
            @ForAll("consentGrantStates") boolean[] grantStates
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        ConsentType[] types = ConsentType.values();
        boolean allGranted = true;

        for (int i = 0; i < types.length; i++) {
            if (grantStates[i]) {
                Consent consent = Consent.builder()
                        .userId(userId)
                        .consentType(types[i].name())
                        .granted(true)
                        .build();
                when(consentRepository.findByUserIdAndType(userId, types[i].name()))
                        .thenReturn(Optional.of(consent));
            } else {
                allGranted = false;
                when(consentRepository.findByUserIdAndType(userId, types[i].name()))
                        .thenReturn(Optional.empty());
            }
        }

        boolean result = consentService.hasAllRequiredConsents(userId);
        assertThat(result).isEqualTo(allGranted);
    }

    // ========================================================================
    // Property 11: Invalid consent type rejection
    // ========================================================================

    /**
     * Property 11a: For any string that is NOT one of the valid consent types,
     * validateConsentType throws IllegalArgumentException.
     *
     * Validates: Requirements 6.7
     */
    @Property(tries = 100)
    @Tag("Property 11: Invalid consent type rejection")
    void validateConsentType_rejectsInvalidTypes(
            @ForAll("invalidConsentTypes") String invalidType
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        assertThatThrownBy(() -> consentService.validateConsentType(invalidType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid consent type");
    }

    /**
     * Property 11b: For any valid ConsentType name string,
     * validateConsentType returns the corresponding enum value.
     *
     * Validates: Requirements 6.7
     */
    @Property(tries = 100)
    @Tag("Property 11: Invalid consent type rejection")
    void validateConsentType_acceptsValidTypes(
            @ForAll("consentTypes") ConsentType type
    ) {
        ConsentRepository consentRepository = mock(ConsentRepository.class);
        AuditService auditService = mock(AuditService.class);
        ConsentService consentService = new ConsentService(consentRepository, auditService);

        ConsentType result = consentService.validateConsentType(type.name());
        assertThat(result).isEqualTo(type);
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(3)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<ConsentType> consentTypes() {
        return Arbitraries.of(ConsentType.values());
    }

    @Provide
    Arbitrary<String> versions() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .withChars('.')
                .ofMinLength(1)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String> ipAddresses() {
        Arbitrary<Integer> octet = Arbitraries.integers().between(0, 255);
        return Combinators.combine(octet, octet, octet, octet)
                .as((a, b, c, d) -> a + "." + b + "." + c + "." + d);
    }

    @Provide
    Arbitrary<String> invalidConsentTypes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(30)
                .filter(s -> !VALID_CONSENT_TYPES.contains(s));
    }

    @Provide
    Arbitrary<boolean[]> consentGrantStates() {
        // Generate arrays of 3 booleans, one for each ConsentType
        return Arbitraries.of(true, false)
                .list()
                .ofSize(ConsentType.values().length)
                .map(list -> {
                    boolean[] arr = new boolean[list.size()];
                    for (int i = 0; i < list.size(); i++) {
                        arr[i] = list.get(i);
                    }
                    return arr;
                });
    }
}
