package io.asbun.backend.service;

import io.asbun.backend.model.Consent;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.repository.ConsentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock
    private ConsentRepository consentRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private ConsentService consentService;

    private static final String USER_ID = "user-123";
    private static final String VERSION = "1.0";
    private static final String IP_ADDRESS = "192.168.1.1";

    @Test
    void grantConsent_newConsent_createsRecord() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.grantConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, VERSION, IP_ADDRESS);

        assertThat(result.getUserId()).isEqualTo(USER_ID);
        assertThat(result.getConsentType()).isEqualTo(ConsentType.AI_DATA_PROCESSING.name());
        assertThat(result.getGranted()).isTrue();
        assertThat(result.getGrantedAt()).isNotNull();
        assertThat(result.getVersion()).isEqualTo(VERSION);
        assertThat(result.getIpAddress()).isEqualTo(IP_ADDRESS);

        verify(consentRepository).save(any(Consent.class));
        verify(auditService).logEvent(eq(USER_ID), eq(AuditEventType.CONSENT_GRANTED), anyMap(), eq(IP_ADDRESS), isNull());
    }

    @Test
    void grantConsent_existingConsent_updatesRecord() {
        Consent existing = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.PRIVACY_POLICY.name())
                .granted(false)
                .grantedAt(Instant.now().minusSeconds(3600))
                .version("0.9")
                .ipAddress("10.0.0.1")
                .build();

        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.PRIVACY_POLICY.name()))
                .thenReturn(Optional.of(existing));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.grantConsent(USER_ID, ConsentType.PRIVACY_POLICY, VERSION, IP_ADDRESS);

        assertThat(result.getGranted()).isTrue();
        assertThat(result.getVersion()).isEqualTo(VERSION);
        assertThat(result.getIpAddress()).isEqualTo(IP_ADDRESS);
        assertThat(result.getGrantedAt()).isNotNull();

        verify(consentRepository).save(existing);
    }

    @Test
    void grantConsent_logsAuditEvent() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.TERMS_OF_SERVICE.name()))
                .thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        consentService.grantConsent(USER_ID, ConsentType.TERMS_OF_SERVICE, VERSION, IP_ADDRESS);

        ArgumentCaptor<Map<String, String>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).logEvent(eq(USER_ID), eq(AuditEventType.CONSENT_GRANTED),
                detailsCaptor.capture(), eq(IP_ADDRESS), isNull());

        Map<String, String> details = detailsCaptor.getValue();
        assertThat(details).containsEntry("consentType", ConsentType.TERMS_OF_SERVICE.name());
        assertThat(details).containsEntry("version", VERSION);
    }

    @Test
    void revokeConsent_existingConsent_setsRevokedFields() {
        Instant originalGrantedAt = Instant.now().minusSeconds(7200);
        Consent existing = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(true)
                .grantedAt(originalGrantedAt)
                .version(VERSION)
                .ipAddress("10.0.0.1")
                .build();

        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(existing));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent result = consentService.revokeConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, IP_ADDRESS);

        assertThat(result.getGranted()).isFalse();
        assertThat(result.getRevokedAt()).isNotNull();
        // Original grant fields preserved
        assertThat(result.getGrantedAt()).isEqualTo(originalGrantedAt);
        assertThat(result.getVersion()).isEqualTo(VERSION);

        verify(consentRepository).save(existing);
        verify(auditService).logEvent(eq(USER_ID), eq(AuditEventType.CONSENT_REVOKED), anyMap(), eq(IP_ADDRESS), isNull());
    }

    @Test
    void revokeConsent_noExistingRecord_throwsIllegalStateException() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> consentService.revokeConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, IP_ADDRESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No consent record found");
    }

    @Test
    void getConsents_returnsAllConsentsForUser() {
        List<Consent> consents = List.of(
                Consent.builder().userId(USER_ID).consentType(ConsentType.TERMS_OF_SERVICE.name()).granted(true).build(),
                Consent.builder().userId(USER_ID).consentType(ConsentType.PRIVACY_POLICY.name()).granted(true).build()
        );
        when(consentRepository.findAllByUserId(USER_ID)).thenReturn(consents);

        List<Consent> result = consentService.getConsents(USER_ID);

        assertThat(result).hasSize(2);
    }

    @Test
    void hasActiveConsent_grantedTrue_returnsTrue() {
        Consent consent = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(true)
                .build();
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(consent));

        boolean result = consentService.hasActiveConsent(USER_ID, ConsentType.AI_DATA_PROCESSING);

        assertThat(result).isTrue();
    }

    @Test
    void hasActiveConsent_grantedFalse_returnsFalse() {
        Consent consent = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(false)
                .build();
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(consent));

        boolean result = consentService.hasActiveConsent(USER_ID, ConsentType.AI_DATA_PROCESSING);

        assertThat(result).isFalse();
    }

    @Test
    void hasActiveConsent_noRecord_returnsFalse() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.PRIVACY_POLICY.name()))
                .thenReturn(Optional.empty());

        boolean result = consentService.hasActiveConsent(USER_ID, ConsentType.PRIVACY_POLICY);

        assertThat(result).isFalse();
    }

    @Test
    void hasAllRequiredConsents_allGranted_returnsTrue() {
        for (ConsentType type : ConsentType.values()) {
            Consent consent = Consent.builder()
                    .userId(USER_ID)
                    .consentType(type.name())
                    .granted(true)
                    .build();
            when(consentRepository.findByUserIdAndType(USER_ID, type.name()))
                    .thenReturn(Optional.of(consent));
        }

        boolean result = consentService.hasAllRequiredConsents(USER_ID);

        assertThat(result).isTrue();
    }

    @Test
    void hasAllRequiredConsents_oneMissing_returnsFalse() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.TERMS_OF_SERVICE.name()))
                .thenReturn(Optional.of(Consent.builder().granted(true).build()));
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.PRIVACY_POLICY.name()))
                .thenReturn(Optional.of(Consent.builder().granted(true).build()));
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.empty());

        boolean result = consentService.hasAllRequiredConsents(USER_ID);

        assertThat(result).isFalse();
    }

    @Test
    void hasAllRequiredConsents_oneRevoked_returnsFalse() {
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.TERMS_OF_SERVICE.name()))
                .thenReturn(Optional.of(Consent.builder().granted(true).build()));
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.PRIVACY_POLICY.name()))
                .thenReturn(Optional.of(Consent.builder().granted(false).build()));

        boolean result = consentService.hasAllRequiredConsents(USER_ID);

        assertThat(result).isFalse();
    }

    @Test
    void validateConsentType_validType_returnsEnum() {
        ConsentType result = consentService.validateConsentType("TERMS_OF_SERVICE");
        assertThat(result).isEqualTo(ConsentType.TERMS_OF_SERVICE);
    }

    @Test
    void validateConsentType_invalidType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> consentService.validateConsentType("INVALID_TYPE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid consent type");
    }

    @Test
    void validateConsentType_nullType_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> consentService.validateConsentType(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid consent type");
    }
}
