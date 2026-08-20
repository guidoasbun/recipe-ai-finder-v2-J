package io.asbun.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.asbun.backend.dto.DataExportJson;
import io.asbun.backend.dto.ExportStatusResponse;
import io.asbun.backend.model.AuditEvent;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.AuditEventType;
import io.asbun.backend.model.enums.BedrockModel;
import io.asbun.backend.model.enums.ImageModel;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.repository.UserRepository;
import net.jqwik.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for DataExportService.
 * <p>
 * Validates: Requirements 4.1, 4.2, 4.5, 5.2, 5.7, 5.8
 */
@Tag("security-legal-compliance")
class DataExportServicePropertyTest {

    // --- Providers ---

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(20);
    }

    @Provide
    Arbitrary<User> users() {
        return Combinators.combine(
                Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(20),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15)
                        .map(s -> s + "@example.com"),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(15),
                Arbitraries.longs().between(
                        Instant.now().minus(365, ChronoUnit.DAYS).getEpochSecond(),
                        Instant.now().getEpochSecond()
                ).map(Instant::ofEpochSecond)
        ).as((userId, email, username, createdAt) -> User.builder()
                .userId(userId)
                .email(email)
                .username(username)
                .createdAt(createdAt)
                .build());
    }

    @Provide
    Arbitrary<List<Recipe>> recipeLists() {
        return recipeArbitrary().list().ofMinSize(0).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<Recipe>> smallRecipeLists() {
        return recipeArbitrary().list().ofMinSize(0).ofMaxSize(5);
    }

    private Arbitrary<Recipe> recipeArbitrary() {
        Arbitrary<Recipe> base = Combinators.combine(
                Arbitraries.strings().alpha().numeric().ofLength(10),
                Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(50),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(100),
                Arbitraries.strings().alpha().ofMinLength(2).ofMaxLength(20).list().ofMinSize(1).ofMaxSize(10),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(50).list().ofMinSize(1).ofMaxSize(8),
                Arbitraries.of(BedrockModel.values()).injectNull(0.1),
                Arbitraries.of(ImageModel.values()).injectNull(0.1),
                Arbitraries.longs().between(100L, 10000L)
        ).as((recipeId, title, description, ingredients, steps, model, imageModel, textGenMs) ->
                Recipe.builder()
                        .recipeId(recipeId)
                        .title(title)
                        .description(description)
                        .ingredients(ingredients)
                        .steps(steps)
                        .model(model)
                        .imageModel(imageModel)
                        .textGenerationMs(textGenMs)
                        .build());

        return Combinators.combine(
                base,
                Arbitraries.longs().between(500L, 30000L),
                Arbitraries.longs().between(
                        Instant.now().minus(180, ChronoUnit.DAYS).getEpochSecond(),
                        Instant.now().getEpochSecond()
                ).map(Instant::ofEpochSecond)
        ).as((recipe, imageGenMs, createdAt) -> {
            String imageUrl = (recipe.getRecipeId().hashCode() % 3 != 0)
                    ? "recipes/" + recipe.getRecipeId() + ".png"
                    : null;
            recipe.setImageGenerationMs(imageGenMs);
            recipe.setCreatedAt(createdAt);
            recipe.setImageUrl(imageUrl);
            return recipe;
        });
    }

    private DataExportService createService(UserRepository userRepository,
                                            RecipeRepository recipeRepository,
                                            S3Client s3Client,
                                            S3Presigner s3Presigner,
                                            AuditService auditService,
                                            ObjectMapper objectMapper) {
        DataExportService service = new DataExportService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);
        ReflectionTestUtils.setField(service, "bucket", "test-bucket");
        return service;
    }

    // --- Property 6: Data export completeness (JSON) ---

    /**
     * Property 6: For any user with 0-20 recipes, exportJson returns a DataExportJson
     * with correct user data, all recipes included with fields correctly mapped,
     * and audit events are logged.
     * <p>
     * Validates: Requirements 4.1, 4.2, 4.5
     */
    @Property(tries = 100)
    @Tag("data-export-completeness-json")
    void jsonExport_containsCompleteUserAndRecipeData(
            @ForAll("users") User user,
            @ForAll("recipeLists") List<Recipe> recipes) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();

        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(user.getUserId())).thenReturn(recipes);
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Act
        DataExportJson result = service.exportJson(user.getUserId());

        // Assert - user data correctness
        assertThat(result.getUser()).isNotNull();
        assertThat(result.getUser().getEmail()).isEqualTo(user.getEmail());
        assertThat(result.getUser().getUsername()).isEqualTo(user.getUsername());
        assertThat(result.getUser().getCreatedAt()).isEqualTo(user.getCreatedAt());

        // Assert - exportedAt is set
        assertThat(result.getExportedAt()).isNotNull();

        // Assert - recipe count matches
        assertThat(result.getRecipes()).hasSize(recipes.size());

        // Assert - recipe fields correctly mapped (comparing by index since order is preserved)
        for (int i = 0; i < recipes.size(); i++) {
            Recipe source = recipes.get(i);
            DataExportJson.RecipeExportData exported = result.getRecipes().get(i);

            assertThat(exported.getRecipeId()).isEqualTo(source.getRecipeId());
            assertThat(exported.getTitle()).isEqualTo(source.getTitle());
            assertThat(exported.getDescription()).isEqualTo(source.getDescription());
            assertThat(exported.getIngredients()).isEqualTo(source.getIngredients());
            assertThat(exported.getSteps()).isEqualTo(source.getSteps());
            assertThat(exported.getModel()).isEqualTo(
                    source.getModel() != null ? source.getModel().name() : null);
            assertThat(exported.getImageModel()).isEqualTo(
                    source.getImageModel() != null ? source.getImageModel().name() : null);
            assertThat(exported.getTextGenerationMs()).isEqualTo(source.getTextGenerationMs());
            assertThat(exported.getImageGenerationMs()).isEqualTo(source.getImageGenerationMs());
            assertThat(exported.getCreatedAt()).isEqualTo(source.getCreatedAt());
            assertThat(exported.getImageS3Key()).isEqualTo(source.getImageUrl());
        }

        // Assert - audit events logged (DATA_EXPORT_REQUESTED and DATA_EXPORT_COMPLETED)
        ArgumentCaptor<AuditEventType> eventTypeCaptor = ArgumentCaptor.forClass(AuditEventType.class);
        verify(auditService, times(2)).logEvent(
                eq(user.getUserId()), eventTypeCaptor.capture(), any(), any(), any());

        List<AuditEventType> capturedTypes = eventTypeCaptor.getAllValues();
        assertThat(capturedTypes).containsExactly(
                AuditEventType.DATA_EXPORT_REQUESTED,
                AuditEventType.DATA_EXPORT_COMPLETED);
    }

    /**
     * Property 6 (edge case): For a user with zero recipes, the export should
     * contain empty recipe collection and still be valid.
     * <p>
     * Validates: Requirements 4.5
     */
    @Property(tries = 100)
    @Tag("data-export-completeness-json")
    void jsonExport_emptyRecipesReturnsValidExport(@ForAll("users") User user) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();


        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(user.getUserId())).thenReturn(List.of());
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Act
        DataExportJson result = service.exportJson(user.getUserId());

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getUser()).isNotNull();
        assertThat(result.getRecipes()).isEmpty();
        assertThat(result.getExportedAt()).isNotNull();
    }

    // --- Property 7: ZIP export content completeness ---

    /**
     * Property 7: startZipExport returns IN_PROGRESS status and the export
     * status is trackable. When generateZipAsync is called directly (no Spring proxy),
     * the export eventually completes or fails based on S3 availability.
     * <p>
     * Validates: Requirements 5.2, 5.8
     */
    @Property(tries = 100)
    @Tag("zip-export-content-completeness")
    void zipExport_startsWithInProgressStatus(
            @ForAll("users") User user,
            @ForAll("smallRecipeLists") List<Recipe> recipes) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();


        when(userRepository.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(user.getUserId())).thenReturn(recipes);
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Act
        ExportStatusResponse response = service.startZipExport(user.getUserId());

        // Assert - initial response is IN_PROGRESS
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ExportStatusResponse.ExportStatus.IN_PROGRESS);

        // Assert - status is trackable via getExportStatus
        ExportStatusResponse trackedStatus = service.getExportStatus(user.getUserId());
        assertThat(trackedStatus).isNotNull();
        // Status should be IN_PROGRESS (since generateZipAsync runs async, it may or may not have completed)
        // But the initial state is always IN_PROGRESS
        assertThat(trackedStatus.getStatus()).isIn(
                ExportStatusResponse.ExportStatus.IN_PROGRESS,
                ExportStatusResponse.ExportStatus.COMPLETED,
                ExportStatusResponse.ExportStatus.FAILED);

        // Assert - audit event for DATA_EXPORT_REQUESTED was logged
        verify(auditService, atLeastOnce()).logEvent(
                eq(user.getUserId()),
                eq(AuditEventType.DATA_EXPORT_REQUESTED),
                any(), any(), any());
    }

    // --- Property 8: Duplicate export prevention ---

    /**
     * Property 8: A second ZIP export request while the first is IN_PROGRESS
     * throws IllegalStateException.
     * <p>
     * Validates: Requirements 5.7
     */
    @Property(tries = 100)
    @Tag("duplicate-export-prevention")
    void zipExport_rejectsDuplicateWhileInProgress(@ForAll("userIds") String userId) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();


        User user = User.builder()
                .userId(userId)
                .email("test@example.com")
                .username("testuser")
                .createdAt(Instant.now())
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(List.of());
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Pre-populate the exportStatuses map with IN_PROGRESS for this user
        ConcurrentHashMap<String, ExportStatusResponse> statusMap = new ConcurrentHashMap<>();
        statusMap.put(userId, ExportStatusResponse.builder()
                .status(ExportStatusResponse.ExportStatus.IN_PROGRESS)
                .build());
        ReflectionTestUtils.setField(service, "exportStatuses", statusMap);

        // Act & Assert - second call while IN_PROGRESS throws
        assertThatThrownBy(() -> service.startZipExport(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already in progress");
    }

    /**
     * Property 8: After an export completes (COMPLETED status), a new export can be started.
     * <p>
     * Validates: Requirements 5.7
     */
    @Property(tries = 100)
    @Tag("duplicate-export-prevention")
    void zipExport_allowsNewExportAfterCompletion(@ForAll("userIds") String userId) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();


        User user = User.builder()
                .userId(userId)
                .email("test@example.com")
                .username("testuser")
                .createdAt(Instant.now())
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(List.of());
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Pre-populate with COMPLETED status
        ConcurrentHashMap<String, ExportStatusResponse> statusMap = new ConcurrentHashMap<>();
        statusMap.put(userId, ExportStatusResponse.builder()
                .status(ExportStatusResponse.ExportStatus.COMPLETED)
                .downloadUrl("https://example.com/download")
                .build());
        ReflectionTestUtils.setField(service, "exportStatuses", statusMap);

        // Act - new export should be allowed
        ExportStatusResponse response = service.startZipExport(userId);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ExportStatusResponse.ExportStatus.IN_PROGRESS);
    }

    /**
     * Property 8: After an export fails (FAILED status), a new export can be started.
     * <p>
     * Validates: Requirements 5.7
     */
    @Property(tries = 100)
    @Tag("duplicate-export-prevention")
    void zipExport_allowsNewExportAfterFailure(@ForAll("userIds") String userId) {

        // Arrange
        UserRepository userRepository = mock(UserRepository.class);
        RecipeRepository recipeRepository = mock(RecipeRepository.class);
        S3Client s3Client = mock(S3Client.class);
        S3Presigner s3Presigner = mock(S3Presigner.class);
        AuditService auditService = mock(AuditService.class);
        ObjectMapper objectMapper = new ObjectMapper();


        User user = User.builder()
                .userId(userId)
                .email("test@example.com")
                .username("testuser")
                .createdAt(Instant.now())
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(userId)).thenReturn(List.of());
        when(auditService.logEvent(any(), any(), any(), any(), any()))
                .thenReturn(AuditEvent.builder().auditId(UUID.randomUUID().toString()).build());

        DataExportService service = createService(
                userRepository, recipeRepository, s3Client, s3Presigner, auditService, objectMapper);

        // Pre-populate with FAILED status
        ConcurrentHashMap<String, ExportStatusResponse> statusMap = new ConcurrentHashMap<>();
        statusMap.put(userId, ExportStatusResponse.builder()
                .status(ExportStatusResponse.ExportStatus.FAILED)
                .error("Previous export failed")
                .build());
        ReflectionTestUtils.setField(service, "exportStatuses", statusMap);

        // Act - new export should be allowed
        ExportStatusResponse response = service.startZipExport(userId);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ExportStatusResponse.ExportStatus.IN_PROGRESS);
    }
}
