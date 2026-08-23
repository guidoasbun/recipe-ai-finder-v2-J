package io.asbun.backend.integration;

import io.asbun.backend.config.RateLimitFilter;
import io.asbun.backend.controller.AccountController;
import io.asbun.backend.controller.ConsentController;
import io.asbun.backend.controller.RecipeController;
import io.asbun.backend.dto.*;
import io.asbun.backend.model.AuditEvent;
import io.asbun.backend.model.Consent;
import io.asbun.backend.model.Recipe;
import io.asbun.backend.model.User;
import io.asbun.backend.model.enums.*;
import io.asbun.backend.repository.AuditRepository;
import io.asbun.backend.repository.ConsentRepository;
import io.asbun.backend.repository.RecipeRepository;
import io.asbun.backend.repository.UserRepository;
import io.asbun.backend.service.*;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminDeleteUserResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the full compliance lifecycle.
 * Tests services and controllers working together with mocked repositories and AWS clients.
 *
 * Validates: Requirements 16.1–16.7
 */
@ExtendWith(MockitoExtension.class)
class ComplianceLifecycleIntegrationTest {

    // Repositories
    @Mock private UserRepository userRepository;
    @Mock private RecipeRepository recipeRepository;
    @Mock private ConsentRepository consentRepository;
    @Mock private AuditRepository auditRepository;

    // AWS clients
    @Mock private S3Service s3Service;
    @Mock private CognitoIdentityProviderClient cognitoClient;
    @Mock private JwtDecoder jwtDecoder;

    // Services (constructed with mocked dependencies)
    private AuditService auditService;
    private ConsentService consentService;
    private AccountDeletionService accountDeletionService;

    // Controllers
    private AccountController accountController;
    private ConsentController consentController;
    private RecipeController recipeController;

    // Mocked service used in RecipeController
    private BedrockService bedrockService;

    // Test data
    private static final String USER_ID = "user-integration-001";
    private static final String EMAIL = "test@example.com";
    private static final String USERNAME = "testuser";
    private static final String IP_ADDRESS = "192.168.1.100";

    @BeforeEach
    void setUp() {
        // Wire up the service layer with mocked repositories
        auditService = new AuditService(auditRepository);
        consentService = new ConsentService(consentRepository, auditService);
        accountDeletionService = new AccountDeletionService(
                userRepository, recipeRepository, s3Service, auditService, cognitoClient);
        ReflectionTestUtils.setField(accountDeletionService, "userPoolId", "us-east-1_TestPool");

        // DataExportService requires ObjectMapper, S3Client, S3Presigner — we test it separately
        // For the integration tests that need it, we set up inline

        // Wire controllers
        accountController = new AccountController(accountDeletionService, null, userRepository);
        consentController = new ConsentController(consentService);

        // RecipeController requires more dependencies
        RecipeService recipeService = mock(RecipeService.class);
        bedrockService = mock(BedrockService.class);
        ImageSseService imageSseService = mock(ImageSseService.class);
        recipeController = new RecipeController(
                recipeService, bedrockService, userRepository, imageSseService, consentService);
        ReflectionTestUtils.setField(recipeController, "testEmail", "testdemo@example.com");
        ReflectionTestUtils.setField(recipeController, "generateCallLimit", 10);
    }

    // ========================================================================
    // Test 1: Full Account Lifecycle
    // Validates: Requirement 16.1
    // ========================================================================

    @Test
    @DisplayName("Full account lifecycle: consent → soft delete → cancel → hard delete → verify clean")
    void testFullAccountLifecycle() {
        // --- Phase 1: User exists and is active ---
        User user = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .username(USERNAME)
                .createdAt(Instant.now().minus(60, ChronoUnit.DAYS))
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        // --- Phase 2: Grant all consents ---
        when(consentRepository.findByUserIdAndType(eq(USER_ID), anyString())).thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        Consent tosConsent = consentService.grantConsent(USER_ID, ConsentType.TERMS_OF_SERVICE, "1.0", IP_ADDRESS);
        Consent ppConsent = consentService.grantConsent(USER_ID, ConsentType.PRIVACY_POLICY, "1.0", IP_ADDRESS);
        Consent aiConsent = consentService.grantConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, "1.0", IP_ADDRESS);

        assertThat(tosConsent.getGranted()).isTrue();
        assertThat(ppConsent.getGranted()).isTrue();
        assertThat(aiConsent.getGranted()).isTrue();

        // Verify consent audit events were logged (3 CONSENT_GRANTED events)
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository, times(3)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .allMatch(e -> e.getEventType().equals(AuditEventType.CONSENT_GRANTED.name()));

        // --- Phase 3: Soft delete ---
        reset(auditRepository);
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        accountDeletionService.requestSoftDeletion(USER_ID);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.PENDING_DELETION);
        assertThat(user.getScheduledDeletionDate()).isNotNull();
        assertThat(user.getScheduledDeletionDate()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));

        // Verify ACCOUNT_DELETION_REQUESTED audit event
        verify(auditRepository).save(argThat(event ->
                AuditEventType.ACCOUNT_DELETION_REQUESTED.name().equals(event.getEventType())));

        // --- Phase 4: Cancel deletion ---
        reset(auditRepository);
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        accountDeletionService.cancelDeletion(USER_ID);

        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(user.getDeletionRequestedAt()).isNull();
        assertThat(user.getScheduledDeletionDate()).isNull();

        // Verify ACCOUNT_REACTIVATED audit event
        verify(auditRepository).save(argThat(event ->
                AuditEventType.ACCOUNT_REACTIVATED.name().equals(event.getEventType())));

        // --- Phase 5: Hard delete ---
        reset(auditRepository);
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        Recipe recipe1 = Recipe.builder()
                .recipeId("recipe-1")
                .userId(USER_ID)
                .title("Test Recipe 1")
                .imageUrl("recipes/recipe-1.png")
                .build();
        Recipe recipe2 = Recipe.builder()
                .recipeId("recipe-2")
                .userId(USER_ID)
                .title("Test Recipe 2")
                .imageUrl("recipes/recipe-2.png")
                .build();

        when(recipeRepository.findByUserId(USER_ID)).thenReturn(List.of(recipe1, recipe2));
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        accountDeletionService.executeHardDeletion(USER_ID);

        // Verify all data was purged
        verify(s3Service).deleteImage("recipes/recipe-1.png");
        verify(s3Service).deleteImage("recipes/recipe-2.png");
        verify(recipeRepository).delete("recipe-1");
        verify(recipeRepository).delete("recipe-2");
        verify(userRepository).delete(USER_ID);
        verify(cognitoClient).adminDeleteUser(argThat((AdminDeleteUserRequest req) ->
                req.username().equals(USER_ID) && req.userPoolId().equals("us-east-1_TestPool")));

        // Verify ACCOUNT_DELETION_COMPLETED audit event
        verify(auditRepository).save(argThat(event ->
                AuditEventType.ACCOUNT_DELETION_COMPLETED.name().equals(event.getEventType())));
    }

    // ========================================================================
    // Test 2: Consent Flow Blocks Recipe Generation
    // Validates: Requirement 16.2
    // ========================================================================

    @Test
    @DisplayName("Consent flow blocks recipe generation without AI_DATA_PROCESSING consent")
    void testConsentFlowBlocksRecipeGeneration() {
        // User is active but has no AI_DATA_PROCESSING consent
        User activeUser = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser));
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.empty());

        JwtAuthenticationToken auth = createMockAuthentication(USER_ID, EMAIL);
        GenerateRecipeRequest request = new GenerateRecipeRequest();
        request.setIngredients(List.of("chicken", "rice"));
        request.setModel(BedrockModel.CLAUDE_SONNET);

        // Attempt generation without consent — should get 403
        ResponseEntity<?> response = recipeController.generateRecipes(request, auth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("AI data processing consent is required");

        // Now grant the consent
        Consent grantedConsent = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(true)
                .grantedAt(Instant.now())
                .build();
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(grantedConsent));

        // Mock BedrockService to return valid recipes when consent check passes
        List<GenerateRecipeResponse> mockRecipes = List.of(
                GenerateRecipeResponse.builder()
                        .title("Chicken Rice Bowl")
                        .description("A simple bowl")
                        .ingredients(List.of("chicken", "rice"))
                        .steps(List.of("Cook rice", "Grill chicken", "Combine"))
                        .build()
        );
        when(bedrockService.generateRecipes(anyList(), any(BedrockModel.class))).thenReturn(mockRecipes);

        // Now the recipe generation should pass the consent check and succeed
        ResponseEntity<?> responseAfterConsent = recipeController.generateRecipes(request, auth);
        assertThat(responseAfterConsent.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ========================================================================
    // Test 3: Rate Limiting Returns 429 with Retry-After
    // Validates: Requirement 16.3
    // ========================================================================

    @Test
    @DisplayName("Rate limiting returns 429 with Retry-After header when limit exceeded")
    void testRateLimitReturns429() throws Exception {
        RateLimitFilter rateLimitFilter = new RateLimitFilter(jwtDecoder);

        // Mock JWT decoding for authenticated user
        Jwt mockJwt = mock(Jwt.class);
        when(mockJwt.getSubject()).thenReturn(USER_ID);
        when(jwtDecoder.decode(anyString())).thenReturn(mockJwt);

        FilterChain filterChain = mock(FilterChain.class);

        // DELETION category has a limit of 5 per hour
        // Make 5 successful requests
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/account/delete");
            request.addHeader("Authorization", "Bearer valid-token-" + i);
            MockHttpServletResponse response = new MockHttpServletResponse();

            rateLimitFilter.doFilter(request, response, filterChain);
            assertThat(response.getStatus()).isEqualTo(200); // Passed through
        }

        // The 6th request should be rate limited
        MockHttpServletRequest rateLimitedRequest = new MockHttpServletRequest("POST", "/api/account/delete");
        rateLimitedRequest.addHeader("Authorization", "Bearer valid-token-extra");
        MockHttpServletResponse rateLimitedResponse = new MockHttpServletResponse();

        rateLimitFilter.doFilter(rateLimitedRequest, rateLimitedResponse, filterChain);

        assertThat(rateLimitedResponse.getStatus()).isEqualTo(429);
        assertThat(rateLimitedResponse.getHeader("Retry-After")).isNotNull();
        int retryAfter = Integer.parseInt(rateLimitedResponse.getHeader("Retry-After"));
        assertThat(retryAfter).isPositive();

        // Verify filterChain was called 5 times (not 6)
        verify(filterChain, times(5)).doFilter(any(), any());
    }

    // ========================================================================
    // Test 4: Audit Log Entries for All Compliance Event Types
    // Validates: Requirement 16.4
    // ========================================================================

    @Test
    @DisplayName("Audit log entries are persisted for all compliance event types")
    void testAuditLogEntriesForAllComplianceEventTypes() {
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.findByUserIdAndType(eq(USER_ID), anyString())).thenReturn(Optional.empty());
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .username(USERNAME)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(Instant.now().minus(30, ChronoUnit.DAYS))
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recipeRepository.findByUserId(USER_ID)).thenReturn(List.of());
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        // Trigger CONSENT_GRANTED
        consentService.grantConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, "1.0", IP_ADDRESS);

        // Trigger CONSENT_REVOKED
        Consent existingConsent = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(true)
                .grantedAt(Instant.now())
                .version("1.0")
                .build();
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(existingConsent));
        consentService.revokeConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, IP_ADDRESS);

        // Trigger ACCOUNT_DELETION_REQUESTED (soft delete)
        user.setAccountStatus(AccountStatus.ACTIVE); // reset
        accountDeletionService.requestSoftDeletion(USER_ID);

        // Trigger ACCOUNT_REACTIVATED (cancel)
        accountDeletionService.cancelDeletion(USER_ID);

        // Trigger ACCOUNT_DELETION_COMPLETED (hard delete)
        accountDeletionService.executeHardDeletion(USER_ID);

        // Trigger DATA_EXPORT_REQUESTED and DATA_EXPORT_COMPLETED via AuditService directly
        // (DataExportService needs S3Client/S3Presigner which are complex to wire)
        auditService.logEvent(USER_ID, AuditEventType.DATA_EXPORT_REQUESTED,
                Map.of("format", "json"), IP_ADDRESS, null);
        auditService.logEvent(USER_ID, AuditEventType.DATA_EXPORT_COMPLETED,
                Map.of("format", "json", "recipeCount", "3"), IP_ADDRESS, null);

        // Capture all audit events
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepository, atLeast(7)).save(auditCaptor.capture());

        List<String> eventTypes = auditCaptor.getAllValues().stream()
                .map(AuditEvent::getEventType)
                .toList();

        // Verify all required compliance event types are present
        assertThat(eventTypes).contains(
                AuditEventType.CONSENT_GRANTED.name(),
                AuditEventType.CONSENT_REVOKED.name(),
                AuditEventType.ACCOUNT_DELETION_REQUESTED.name(),
                AuditEventType.ACCOUNT_REACTIVATED.name(),
                AuditEventType.ACCOUNT_DELETION_COMPLETED.name(),
                AuditEventType.DATA_EXPORT_REQUESTED.name(),
                AuditEventType.DATA_EXPORT_COMPLETED.name()
        );

        // Verify each event has required fields
        for (AuditEvent event : auditCaptor.getAllValues()) {
            assertThat(event.getAuditId()).isNotBlank();
            assertThat(event.getUserId()).isEqualTo(USER_ID);
            assertThat(event.getEventType()).isNotBlank();
            assertThat(event.getTimestamp()).isNotBlank();
        }
    }

    // ========================================================================
    // Test 5: JSON Export Contains All Expected Fields
    // Validates: Requirement 16.5
    // ========================================================================

    @Test
    @DisplayName("JSON export contains all expected user and recipe fields")
    void testJsonExportContainsAllExpectedFields() {
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .username(USERNAME)
                .createdAt(Instant.parse("2024-06-01T08:00:00Z"))
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        Recipe recipe1 = Recipe.builder()
                .recipeId("recipe-001")
                .userId(USER_ID)
                .title("Grilled Chicken")
                .description("A delicious grilled chicken recipe")
                .ingredients(List.of("chicken breast", "olive oil", "salt", "pepper"))
                .steps(List.of("Season chicken", "Grill for 6 min each side"))
                .model(BedrockModel.CLAUDE_SONNET)
                .imageModel(ImageModel.STABILITY_CORE)
                .textGenerationMs(1500L)
                .imageGenerationMs(3000L)
                .createdAt(Instant.parse("2024-07-15T12:00:00Z"))
                .imageUrl("recipes/recipe-001.png")
                .build();

        Recipe recipe2 = Recipe.builder()
                .recipeId("recipe-002")
                .userId(USER_ID)
                .title("Pasta Carbonara")
                .description("Classic Italian pasta")
                .ingredients(List.of("spaghetti", "eggs", "pancetta", "parmesan"))
                .steps(List.of("Cook pasta", "Fry pancetta", "Mix eggs and cheese", "Combine"))
                .model(BedrockModel.CLAUDE_HAIKU)
                .imageModel(ImageModel.GPT_IMAGE_1_5)
                .textGenerationMs(800L)
                .imageGenerationMs(2500L)
                .createdAt(Instant.parse("2024-08-20T15:30:00Z"))
                .imageUrl("recipes/recipe-002.png")
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(USER_ID)).thenReturn(List.of(recipe1, recipe2));

        // Use DataExportService with mocked dependencies
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        objectMapper.findAndRegisterModules();
        software.amazon.awssdk.services.s3.S3Client mockS3Client = mock(software.amazon.awssdk.services.s3.S3Client.class);
        software.amazon.awssdk.services.s3.presigner.S3Presigner mockS3Presigner = mock(software.amazon.awssdk.services.s3.presigner.S3Presigner.class);

        DataExportService exportService = new DataExportService(
                userRepository, recipeRepository, mockS3Client, mockS3Presigner, auditService, objectMapper);
        ReflectionTestUtils.setField(exportService, "bucket", "recipe-images-test");

        DataExportJson result = exportService.exportJson(USER_ID);

        // Verify export metadata
        assertThat(result.getExportedAt()).isNotNull();

        // Verify user fields
        assertThat(result.getUser()).isNotNull();
        assertThat(result.getUser().getEmail()).isEqualTo(EMAIL);
        assertThat(result.getUser().getUsername()).isEqualTo(USERNAME);
        assertThat(result.getUser().getCreatedAt()).isEqualTo(Instant.parse("2024-06-01T08:00:00Z"));

        // Verify recipes
        assertThat(result.getRecipes()).hasSize(2);

        DataExportJson.RecipeExportData exportedRecipe1 = result.getRecipes().stream()
                .filter(r -> r.getRecipeId().equals("recipe-001"))
                .findFirst().orElseThrow();

        assertThat(exportedRecipe1.getTitle()).isEqualTo("Grilled Chicken");
        assertThat(exportedRecipe1.getDescription()).isEqualTo("A delicious grilled chicken recipe");
        assertThat(exportedRecipe1.getIngredients()).containsExactly("chicken breast", "olive oil", "salt", "pepper");
        assertThat(exportedRecipe1.getSteps()).containsExactly("Season chicken", "Grill for 6 min each side");
        assertThat(exportedRecipe1.getModel()).isEqualTo("CLAUDE_SONNET");
        assertThat(exportedRecipe1.getImageModel()).isEqualTo("STABILITY_CORE");
        assertThat(exportedRecipe1.getTextGenerationMs()).isEqualTo(1500L);
        assertThat(exportedRecipe1.getImageGenerationMs()).isEqualTo(3000L);
        assertThat(exportedRecipe1.getCreatedAt()).isEqualTo(Instant.parse("2024-07-15T12:00:00Z"));
        assertThat(exportedRecipe1.getImageS3Key()).isEqualTo("recipes/recipe-001.png");

        DataExportJson.RecipeExportData exportedRecipe2 = result.getRecipes().stream()
                .filter(r -> r.getRecipeId().equals("recipe-002"))
                .findFirst().orElseThrow();

        assertThat(exportedRecipe2.getTitle()).isEqualTo("Pasta Carbonara");
        assertThat(exportedRecipe2.getModel()).isEqualTo("CLAUDE_HAIKU");
        assertThat(exportedRecipe2.getImageModel()).isEqualTo("GPT_IMAGE_1_5");
        assertThat(exportedRecipe2.getImageS3Key()).isEqualTo("recipes/recipe-002.png");

        // Verify audit events for export
        verify(auditRepository, atLeast(2)).save(argThat(event ->
                event.getEventType().equals(AuditEventType.DATA_EXPORT_REQUESTED.name()) ||
                event.getEventType().equals(AuditEventType.DATA_EXPORT_COMPLETED.name())));
    }

    // ========================================================================
    // Test 6: Revocation of AI Consent Blocks Recipe Generation
    // Validates: Requirement 16.6
    // ========================================================================

    @Test
    @DisplayName("Revoking AI_DATA_PROCESSING consent blocks subsequent recipe generation")
    void testRevocationOfAiConsentBlocksRecipeGeneration() {
        User activeUser = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser));

        // Initially, user has active AI consent
        Consent activeConsent = Consent.builder()
                .userId(USER_ID)
                .consentType(ConsentType.AI_DATA_PROCESSING.name())
                .granted(true)
                .grantedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .version("1.0")
                .build();
        when(consentRepository.findByUserIdAndType(USER_ID, ConsentType.AI_DATA_PROCESSING.name()))
                .thenReturn(Optional.of(activeConsent));

        JwtAuthenticationToken auth = createMockAuthentication(USER_ID, EMAIL);
        GenerateRecipeRequest request = new GenerateRecipeRequest();
        request.setIngredients(List.of("tomato", "basil"));
        request.setModel(BedrockModel.CLAUDE_SONNET);

        // Mock BedrockService to return valid recipes
        List<GenerateRecipeResponse> mockRecipes = List.of(
                GenerateRecipeResponse.builder()
                        .title("Tomato Basil Soup")
                        .description("A fresh soup")
                        .ingredients(List.of("tomato", "basil"))
                        .steps(List.of("Boil tomatoes", "Add basil", "Blend"))
                        .build()
        );
        when(bedrockService.generateRecipes(anyList(), any(BedrockModel.class))).thenReturn(mockRecipes);

        // Recipe generation should succeed (passes consent check)
        ResponseEntity<?> allowedResponse = recipeController.generateRecipes(request, auth);
        assertThat(allowedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Now revoke the consent
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(consentRepository.save(any(Consent.class))).thenAnswer(inv -> inv.getArgument(0));
        consentService.revokeConsent(USER_ID, ConsentType.AI_DATA_PROCESSING, IP_ADDRESS);

        // After revocation, the consent record has granted=false
        activeConsent.setGranted(false);
        activeConsent.setRevokedAt(Instant.now());

        // Recipe generation should now be blocked
        ResponseEntity<?> blockedResponse = recipeController.generateRecipes(request, auth);
        assertThat(blockedResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) blockedResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("AI data processing consent is required");
    }

    // ========================================================================
    // Test 7: Partial Deletion Failure and Retry
    // Validates: Requirement 16.7
    // ========================================================================

    @Test
    @DisplayName("Partial deletion failure marks DELETION_FAILED; retry via scheduled job completes")
    void testPartialDeletionFailureAndRetry() {
        when(auditRepository.save(any(AuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        User user = User.builder()
                .userId(USER_ID)
                .email(EMAIL)
                .username(USERNAME)
                .accountStatus(AccountStatus.PENDING_DELETION)
                .deletionRequestedAt(Instant.now().minus(31, ChronoUnit.DAYS))
                .scheduledDeletionDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .build();

        Recipe recipe = Recipe.builder()
                .recipeId("recipe-fail")
                .userId(USER_ID)
                .title("Fail Recipe")
                .imageUrl("recipes/recipe-fail.png")
                .build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(recipeRepository.findByUserId(USER_ID)).thenReturn(List.of(recipe));

        // First attempt: S3 deletion throws an exception
        doThrow(new RuntimeException("S3 service unavailable"))
                .when(s3Service).deleteImage("recipes/recipe-fail.png");

        // Execute hard deletion — should throw and mark as DELETION_FAILED
        try {
            accountDeletionService.executeHardDeletion(USER_ID);
        } catch (RuntimeException e) {
            assertThat(e.getMessage()).contains("Hard deletion failed");
        }

        // Verify user was marked as DELETION_FAILED
        assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.DELETION_FAILED);
        verify(userRepository, atLeastOnce()).save(user);

        // --- Retry phase: scheduled job picks up DELETION_FAILED users ---
        // Reset S3 mock to succeed on retry
        reset(s3Service);
        doNothing().when(s3Service).deleteImage("recipes/recipe-fail.png");

        // Reset user state to simulate the scheduled job finding it
        user.setAccountStatus(AccountStatus.DELETION_FAILED);
        when(userRepository.findPendingDeletions()).thenReturn(List.of(user));
        when(cognitoClient.adminDeleteUser(any(AdminDeleteUserRequest.class)))
                .thenReturn(AdminDeleteUserResponse.builder().build());

        // Run the scheduled job
        accountDeletionService.processPendingDeletions();

        // Verify the retry completed the deletion
        verify(s3Service).deleteImage("recipes/recipe-fail.png");
        verify(recipeRepository).delete("recipe-fail");
        verify(userRepository).delete(USER_ID);
        verify(cognitoClient).adminDeleteUser(any(AdminDeleteUserRequest.class));

        // Verify SCHEDULED_DELETION_RUN audit event was logged
        verify(auditRepository, atLeastOnce()).save(argThat(event ->
                AuditEventType.SCHEDULED_DELETION_RUN.name().equals(event.getEventType())));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private JwtAuthenticationToken createMockAuthentication(String userId, String email) {
        Jwt jwt = mock(Jwt.class);
        when(jwt.getClaims()).thenReturn(Map.of("sub", userId, "email", email));
        return new JwtAuthenticationToken(jwt);
    }
}
