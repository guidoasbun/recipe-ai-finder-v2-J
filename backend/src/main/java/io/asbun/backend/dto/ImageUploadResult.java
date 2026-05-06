package io.asbun.backend.dto;

public record ImageUploadResult(
        String s3Key,
        Integer width,
        Integer height,
        String imageType,
        Long imageSizeBytes,
        Long generationMs
) {}
