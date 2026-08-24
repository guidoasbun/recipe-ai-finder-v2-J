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
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DataExportService {

    private final UserRepository userRepository;
    private final RecipeRepository recipeRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final DataExportAsyncWorker asyncWorker;

    @Value("${s3.bucket}")
    private String bucket;

    public DataExportJson exportJson(String userId) {
        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_REQUESTED,
                Map.of("format", "json"), null, null);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        List<Recipe> recipes = recipeRepository.findByUserId(userId);

        DataExportJson exportJson = buildDataExportJson(user, recipes);

        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_COMPLETED,
                Map.of("format", "json", "recipeCount", String.valueOf(recipes.size())), null, null);

        return exportJson;
    }

    public ExportStatusResponse startZipExport(String userId) {
        ExportStatusResponse existing = asyncWorker.getExportStatuses().get(userId);
        if (existing != null && existing.getStatus() == ExportStatusResponse.ExportStatus.IN_PROGRESS) {
            throw new IllegalStateException("Export already in progress");
        }

        ExportStatusResponse inProgress = ExportStatusResponse.builder()
                .status(ExportStatusResponse.ExportStatus.IN_PROGRESS)
                .build();
        asyncWorker.getExportStatuses().put(userId, inProgress);

        auditService.logEvent(userId, AuditEventType.DATA_EXPORT_REQUESTED,
                Map.of("format", "zip"), null, null);

        // Delegate to a separate bean so Spring's @Async proxy is honoured
        asyncWorker.generateZipAsync(userId);

        return inProgress;
    }

    public ExportStatusResponse getExportStatus(String userId) {
        return asyncWorker.getExportStatuses().get(userId);
    }

    private DataExportJson buildDataExportJson(User user, List<Recipe> recipes) {
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
                .missingImages(List.of())
                .build();
    }
}
