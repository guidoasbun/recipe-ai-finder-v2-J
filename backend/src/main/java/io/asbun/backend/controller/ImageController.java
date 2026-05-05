package io.asbun.backend.controller;

import io.asbun.backend.service.S3Service;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Validated
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    private final S3Service s3Service;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(
            @RequestParam("recipeId") @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,36}$") String recipeId,
            @RequestParam("file") MultipartFile file) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body("Only image files are allowed");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest().body("File size must not exceed 5MB");
        }
        String key = s3Service.uploadImage(recipeId, file.getBytes());
        return ResponseEntity.ok(key);
    }
}
