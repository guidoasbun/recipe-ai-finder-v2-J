package io.asbun.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.dto.DataExportJson;
import io.asbun.backend.dto.ExportStatusResponse;
import io.asbun.backend.exception.ResourceNotFoundException;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataExportService {

    private final UserRepository userRepository;
    private final RecipeRepository recipeRepository;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${s3.bucket}")
    private String bucket;

    private final ConcurrentHashMap<String, ExportStatusResponse> exportStatuses = new ConcurrentHashMap<>();

    public DataExportJson exportJson(String userId) {
        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_REQUESTED,
                Map.of("format", "json"), null, null);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        List<Recipe> recipes = recipeRepository.findByUserId(userId);

        DataExportJson exportJson = buildDataExportJson(user, recipes, List.of());

        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_COMPLETED,
                Map.of("format", "json", "recipeCount", String.valueOf(recipes.size())), null, null);

        return exportJson;
    }

    public ExportStatusResponse startZipExport(String userId) {
        ExportStatusResponse existing = exportStatuses.get(userId);
        if (existing != null && existing.getStatus() == ExportStatusResponse.ExportStatus.IN_PROGRESS) {
            throw new IllegalStateException("Export already in progress");
        }

        ExportStatusResponse inProgress = ExportStatusResponse.builder()
                .status(ExportStatusResponse.ExportStatus.IN_PROGRESS)
                .build();
        exportStatuses.put(userId, inProgress);

        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_REQUESTED,
                Map.of("format", "zip"), null, null);

        generateZipAsync(userId);

        return inProgress;
    }

    @Async("dataExportExecutor")
    public void generateZipAsync(String userId) {
        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

            List<Recipe> recipes = recipeRepository.findByUserId(userId);
            List<String> missingImages = new ArrayList<>();

            DataExportJson exportJson = buildDataExportJson(user, recipes, missingImages);

            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            try (ZipOutputStream zipOut = new ZipOutputStream(zipBytes)) {
                // Add images first so we can populate missingImages before serializing JSON
                for (Recipe recipe : recipes) {
                    if (recipe.getImageUrl() != null && !recipe.getImageUrl().isBlank()) {
                        try {
                            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(recipe.getImageUrl())
                                    .build();
                            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(getObjectRequest);
                            byte[] imageData = response.readAllBytes();
                            response.close();

                            ZipEntry imageEntry = new ZipEntry("images/" + recipe.getRecipeId() + ".png");
                            zipOut.putNextEntry(imageEntry);
                            zipOut.write(imageData);
                            zipOut.closeEntry();
                        } catch (Exception e) {
                            log.warn("Failed to download image for recipe {}: {}", recipe.getRecipeId(), e.getMessage());
                            missingImages.add(recipe.getImageUrl());
                        }
                    }
                }

                // Update missingImages in export JSON before serializing
                exportJson.setMissingImages(missingImages);

                // Add data.json
                byte[] jsonData = objectMapper.writeValueAsBytes(exportJson);
                ZipEntry jsonEntry = new ZipEntry("data.json");
                zipOut.putNextEntry(jsonEntry);
                zipOut.write(jsonData);
                zipOut.closeEntry();
            }

            // Upload ZIP to S3
            String zipKey = "exports/" + userId + "/export.zip";
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(zipKey)
                    .contentType("application/zip")
                    .build();
            s3Client.putObject(putRequest, RequestBody.fromBytes(zipBytes.toByteArray()));

            // Generate presigned URL valid for 60 minutes
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(60))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(zipKey)
                            .build())
                    .build();
            String downloadUrl = s3Presigner.presignGetObject(presignRequest).url().toString();

            // Update status to COMPLETED
            ExportStatusResponse completed = ExportStatusResponse.builder()
                    .status(ExportStatusResponse.ExportStatus.COMPLETED)
                    .downloadUrl(downloadUrl)
                    .build();
            exportStatuses.put(userId, completed);

            auditService.logEvent(userId, AuditEventType.DATA_EXPORT_COMPLETED,
                    Map.of("format", "zip", "recipeCount", String.valueOf(recipes.size()),
                            "missingImages", String.valueOf(missingImages.size())), null, null);

            log.info("ZIP export completed for user {}: {} recipes, {} missing images",
                    userId, recipes.size(), missingImages.size());

        } catch (Exception e) {
            log.error("ZIP export failed for user {}: {}", userId, e.getMessage(), e);

            ExportStatusResponse failed = ExportStatusResponse.builder()
                    .status(ExportStatusResponse.ExportStatus.FAILED)
                    .error(e.getMessage())
                    .build();
            exportStatuses.put(userId, failed);

            auditService.logEvent(userId, AuditEventType.DATA_EXPORT_FAILED,
                    Map.of("format", "zip", "error", e.getMessage() != null ? e.getMessage() : "Unknown error"),
                    null, null);
        }
    }

    public ExportStatusResponse getExportStatus(String userId) {
        return exportStatuses.get(userId);
    }

    private DataExportJson buildDataExportJson(User user, List<Recipe> recipes, List<String> missingImages) {
        DataExportJson.UserExportData userData = DataExportJson.UserExportData.builder()
                .email(user.getEmail())
                .username(user.getUsername())
                .createdAt(user.getCreatedAt())
                .build();

        List<DataExportJson.RecipeExportData> recipeData = recipes.stream()
                .map(recipe -> DataExportJson.RecipeExportData.builder()
                        .recipeId(recipe.getRecipeId())
                        .title(recipe.getTitle())
                        .description(recipe.getDescription())
                        .ingredients(recipe.getIngredients())
                        .steps(recipe.getSteps())
                        .model(recipe.getModel() != null ? recipe.getModel().name() : null)
                        .imageModel(recipe.getImageModel() != null ? recipe.getImageModel().name() : null)
                        .textGenerationMs(recipe.getTextGenerationMs())
                        .imageGenerationMs(recipe.getImageGenerationMs())
                        .createdAt(recipe.getCreatedAt())
                        .imageS3Key(recipe.getImageUrl())
                        .build())
                .toList();

        return DataExportJson.builder()
                .exportedAt(Instant.now())
                .user(userData)
                .recipes(recipeData)
                .missingImages(missingImages)
                .build();
    }
}
