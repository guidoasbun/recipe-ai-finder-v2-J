package io.asbun.backend.controller;

import io.asbun.backend.dto.UserDto;
import io.asbun.backend.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/user")
    public ResponseEntity<UserDto> upsertUser(Authentication authentication) {
        JwtAuthenticationToken token = (JwtAuthenticationToken) authentication;
        Map<String, Object> claims = token.getToken().getClaims();

        String userId = (String) claims.get("sub");
        String email = (String) claims.get("email");
        String username = (String) claims.getOrDefault("cognito:username", email);

        UserDto user = userService.upsertUser(userId, email, username);
        return ResponseEntity.ok(user);
    }
}
