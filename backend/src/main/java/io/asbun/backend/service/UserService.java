package io.asbun.backend.service;

import io.asbun.backend.dto.UserDto;
import io.asbun.backend.model.User;
import io.asbun.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class UserService {
    
    private final UserRepository userRepository;

    public UserDto upsertUser(String userId, String email, String username) {
    User user = userRepository.findById(userId)
            .orElseGet(() -> User.builder()
                    .userId(userId)
                    .email(email)
                    .username(username)
                    .createdAt(Instant.now())
                    .build());

        userRepository.save(user);

        return UserDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public UserDto getUserById(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new io.asbun.backend.exception.ResourceNotFoundException("User not found: " + userId));

        return UserDto.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();
    }

}
