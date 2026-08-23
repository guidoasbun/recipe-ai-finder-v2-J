package io.asbun.backend.controller;

import io.asbun.backend.dto.GrantConsentRequest;
import io.asbun.backend.model.Consent;
import io.asbun.backend.model.enums.ConsentType;
import io.asbun.backend.service.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/consent")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    @PostMapping
    public ResponseEntity<Consent> grantConsent(
            @Valid @RequestBody GrantConsentRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String userId = getUserId(authentication);
        String ipAddress = extractIpAddress(httpRequest);

        Consent consent = consentService.grantConsent(
                userId, request.getConsentType(), request.getVersion(), ipAddress);
        return ResponseEntity.ok(consent);
    }

    @DeleteMapping("/{type}")
    public ResponseEntity<Consent> revokeConsent(
            @PathVariable String type,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String userId = getUserId(authentication);
        String ipAddress = extractIpAddress(httpRequest);

        ConsentType consentType = consentService.validateConsentType(type);
        Consent consent = consentService.revokeConsent(userId, consentType, ipAddress);
        return ResponseEntity.ok(consent);
    }

    @GetMapping
    public ResponseEntity<List<Consent>> getConsents(Authentication authentication) {
        String userId = getUserId(authentication);
        List<Consent> consents = consentService.getConsents(userId);
        return ResponseEntity.ok(consents);
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }

    private String extractIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
