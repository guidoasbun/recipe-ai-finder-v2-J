package io.asbun.backend.controller;

import io.asbun.backend.service.S3Service;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final S3Service s3Service;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadImage(
            @RequestParam("recipeId") String recipeId,
            @RequestParam("file") MultipartFile file) throws IOException {
        String key = s3Service.uploadImage(recipeId, file.getBytes());
        return ResponseEntity.ok(key);
    }
}
