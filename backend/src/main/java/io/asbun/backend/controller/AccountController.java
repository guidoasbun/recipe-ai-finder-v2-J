package io.asbun.backend.controller;

import io.asbun.backend.dto.DataExportJson;
import io.asbun.backend.dto.DeleteAccountRequest;
import io.asbun.backend.dto.ExportStatusResponse;
import io.asbun.backend.dto.UserDto;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.User;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.AccountDeletionService;
import io.asbun.backend.service.DataExportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/account")
@RequiredArgsConstructor
public class AccountController {

    private final AccountDeletionService accountDeletionService;
    private final DataExportService dataExportService;
    private final UserRepository userRepository;

    @PostMapping("/delete")
    public ResponseEntity<Void> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request,
            Authentication authentication) {
        String userId = getUserId(authentication);

        if ("soft".equals(request.getType())) {
            accountDeletionService.requestSoftDeletion(userId);
        } else {
            accountDeletionService.executeHardDeletion(userId);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/cancel-deletion")
    public ResponseEntity<Void> cancelDeletion(Authentication authentication) {
        String userId = getUserId(authentication);
        accountDeletionService.cancelDeletion(userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/export")
    public ResponseEntity<DataExportJson> exportJson(
            @RequestParam @Pattern(regexp = "^json$", message = "format must be 'json'") String format,
            Authentication authentication) {
        String userId = getUserId(authentication);
        DataExportJson exportData = dataExportService.exportJson(userId);
        return ResponseEntity.ok(exportData);
    }

    @PostMapping("/export")
    public ResponseEntity<ExportStatusResponse> startZipExport(
            @RequestParam @Pattern(regexp = "^zip$", message = "format must be 'zip'") String format,
            Authentication authentication) {
        String userId = getUserId(authentication);
        ExportStatusResponse status = dataExportService.startZipExport(userId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/export/status")
    public ResponseEntity<ExportStatusResponse> getExportStatus(Authentication authentication) {
        String userId = getUserId(authentication);
        ExportStatusResponse status = dataExportService.getExportStatus(userId);
        if (status == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(status);
    }

    @GetMapping("/profile")
    public ResponseEntity<UserDto> getProfile(Authentication authentication) {
        String userId = getUserId(authentication);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        UserDto profile = UserDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .accountStatus(user.getAccountStatus() != null ? user.getAccountStatus().name() : null)
                .scheduledDeletionDate(user.getScheduledDeletionDate())
                .build();

        return ResponseEntity.ok(profile);
    }

    private String getUserId(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        return (String) token.getToken().getClaims().get("sub");
    }
}
