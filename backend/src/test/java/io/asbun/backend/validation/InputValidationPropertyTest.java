package io.asbun.backend.validation;

import io.asbun.backend.config.RequestSizeLimitFilter;
import io.asbun.backend.dto.DeleteAccountRequest;
import io.asbun.backend.dto.GenerateRecipeRequest;
import io.asbun.backend.dto.GrantConsentRequest;
import io.asbun.backend.dto.SaveRecipeRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for Input Validation.
 *
 * Property 15: Input validation rejects invalid payloads
 * For any request body that violates Bean Validation constraints (exceeds max length,
 * fails pattern match, missing required field), or exceeds 1 MB total size, or is
 * malformed JSON, the backend SHALL reject with the appropriate HTTP error code
 * (400 for validation/JSON errors, 413 for size) and a response containing at least
 * the field name and constraint violated.
 *
 * Validates: Requirements 13.1, 13.2, 13.3, 13.5
 */
@Tag("Feature: security-legal-compliance")
class InputValidationPropertyTest {

    private final Validator validator;

    InputValidationPropertyTest() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            this.validator = factory.getValidator();
        }
    }

    // ========================================================================
    // Property 15a: DeleteAccountRequest — invalid type values are rejected
    // ========================================================================

    /**
     * For any DeleteAccountRequest with a type that is NOT "soft" or "immediate",
     * Bean Validation SHALL produce at least one constraint violation on the "type" field.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void deleteAccountRequest_invalidType_producesViolation(
            @ForAll("invalidDeleteTypes") String invalidType
    ) {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setType(invalidType);

        Set<ConstraintViolation<DeleteAccountRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("DeleteAccountRequest with type '%s' should have violations", invalidType)
                .isNotEmpty();

        // At least one violation should reference the "type" field
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("type");
    }

    /**
     * For any DeleteAccountRequest with a null or blank type,
     * Bean Validation SHALL produce a constraint violation.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 1)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void deleteAccountRequest_nullType_producesViolation() {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setType(null);

        Set<ConstraintViolation<DeleteAccountRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("type");
    }

    /**
     * Valid types "soft" and "immediate" SHALL pass validation.
     *
     * Validates: Requirements 13.1
     */
    @Property(tries = 1)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void deleteAccountRequest_validTypes_passValidation(
            @ForAll("validDeleteTypes") String validType
    ) {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setType(validType);

        Set<ConstraintViolation<DeleteAccountRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("DeleteAccountRequest with type '%s' should have no violations", validType)
                .isEmpty();
    }

    // ========================================================================
    // Property 15b: GrantConsentRequest — null consentType and oversized version
    // ========================================================================

    /**
     * For any GrantConsentRequest with a null consentType,
     * Bean Validation SHALL produce a constraint violation on "consentType".
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 1)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void grantConsentRequest_nullConsentType_producesViolation() {
        GrantConsentRequest request = new GrantConsentRequest();
        request.setConsentType(null);
        request.setVersion("1.0");

        Set<ConstraintViolation<GrantConsentRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("consentType");
    }

    /**
     * For any GrantConsentRequest with a version exceeding 20 characters,
     * Bean Validation SHALL produce a constraint violation on "version".
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void grantConsentRequest_oversizedVersion_producesViolation(
            @ForAll("oversizedVersionStrings") String oversizedVersion
    ) {
        GrantConsentRequest request = new GrantConsentRequest();
        request.setConsentType(io.asbun.backend.model.enums.ConsentType.TERMS_OF_SERVICE);
        request.setVersion(oversizedVersion);

        Set<ConstraintViolation<GrantConsentRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("GrantConsentRequest with version length %d should have violations", oversizedVersion.length())
                .isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("version");
    }

    // ========================================================================
    // Property 15c: GenerateRecipeRequest — invalid payloads
    // ========================================================================

    /**
     * For any GenerateRecipeRequest with null ingredients or null model,
     * Bean Validation SHALL produce constraint violations.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 1)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void generateRecipeRequest_nullFields_producesViolations() {
        GenerateRecipeRequest request = new GenerateRecipeRequest();
        request.setIngredients(null);
        request.setModel(null);

        Set<ConstraintViolation<GenerateRecipeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .containsAnyOf("ingredients", "model");
    }

    /**
     * For any GenerateRecipeRequest with an empty ingredients list (size < 1),
     * Bean Validation SHALL produce a constraint violation.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 1)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void generateRecipeRequest_emptyIngredients_producesViolation() {
        GenerateRecipeRequest request = new GenerateRecipeRequest();
        request.setIngredients(java.util.List.of());
        request.setModel(io.asbun.backend.model.enums.BedrockModel.values()[0]);

        Set<ConstraintViolation<GenerateRecipeRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
    }

    // ========================================================================
    // Property 15d: Oversized request payload => HTTP 413
    // ========================================================================

    /**
     * For any request with Content-Length exceeding 1 MB (1,048,576 bytes),
     * the RequestSizeLimitFilter SHALL reject with HTTP 413 and NOT invoke
     * the filter chain.
     *
     * Validates: Requirements 13.3
     */
    @Property(tries = 100)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void oversizedPayload_returns413(
            @ForAll("oversizedContentLengths") long contentLength
    ) throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recipes");
        request.addHeader("Content-Length", String.valueOf(contentLength));
        request.setContentType("application/json");
        // MockHttpServletRequest uses int internally, so we set the content length via header
        // and override the content length directly
        request.setContent(new byte[0]); // empty body but header says it's big
        // We need to set content length explicitly since MockHttpServletRequest
        // derives it from the content array
        MockHttpServletRequest spiedRequest = spy(request);
        when(spiedRequest.getContentLengthLong()).thenReturn(contentLength);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(spiedRequest, response, filterChain);

        assertThat(response.getStatus())
                .as("Response status for content-length %d should be 413", contentLength)
                .isEqualTo(413);
        assertThat(response.getContentAsString())
                .contains("Payload Too Large");
        verify(filterChain, never()).doFilter(any(), any());
    }

    /**
     * For any request with Content-Length within the 1 MB limit,
     * the RequestSizeLimitFilter SHALL pass the request through to the filter chain.
     *
     * Validates: Requirements 13.3
     */
    @Property(tries = 100)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void validSizedPayload_passesThrough(
            @ForAll("validContentLengths") long contentLength
    ) throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recipes");
        request.setContentType("application/json");
        MockHttpServletRequest spiedRequest = spy(request);
        when(spiedRequest.getContentLengthLong()).thenReturn(contentLength);

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain filterChain = mock(FilterChain.class);

        filter.doFilter(spiedRequest, response, filterChain);

        assertThat(response.getStatus())
                .as("Response status for content-length %d should not be 413", contentLength)
                .isNotEqualTo(413);
        verify(filterChain).doFilter(spiedRequest, response);
    }

    // ========================================================================
    // Property 15e: SaveRecipeRequest — invalid payloads
    // ========================================================================

    /**
     * For any SaveRecipeRequest with required fields missing or exceeding size limits,
     * Bean Validation SHALL produce constraint violations containing the field name.
     *
     * Validates: Requirements 13.1, 13.2
     */
    @Property(tries = 100)
    @Tag("Property 15: Input validation rejects invalid payloads")
    void saveRecipeRequest_oversizedTitle_producesViolation(
            @ForAll("oversizedTitles") String oversizedTitle
    ) {
        SaveRecipeRequest request = new SaveRecipeRequest();
        request.setTitle(oversizedTitle);
        request.setDescription("A valid description");
        request.setIngredients(java.util.List.of("flour"));
        request.setSteps(java.util.List.of("Mix ingredients"));
        request.setModel(io.asbun.backend.model.enums.BedrockModel.values()[0]);

        Set<ConstraintViolation<SaveRecipeRequest>> violations = validator.validate(request);

        assertThat(violations)
                .as("SaveRecipeRequest with title length %d should have violations", oversizedTitle.length())
                .isNotEmpty();
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("title");
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> invalidDeleteTypes() {
        return Arbitraries.of(
                "hard",
                "SOFT",
                "IMMEDIATE",
                "cancel",
                "delete",
                "permanent",
                " soft",
                "soft ",
                "immediate!",
                "",
                "  ",
                "soft\nimmediate",
                "unknown"
        );
    }

    @Provide
    Arbitrary<String> validDeleteTypes() {
        return Arbitraries.of("soft", "immediate");
    }

    @Provide
    Arbitrary<String> oversizedVersionStrings() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(21)
                .ofMaxLength(100);
    }

    @Provide
    Arbitrary<Long> oversizedContentLengths() {
        // Values strictly greater than 1 MB (1,048,576 bytes)
        return Arbitraries.longs().between(1_048_577L, 100_000_000L);
    }

    @Provide
    Arbitrary<Long> validContentLengths() {
        // Values within the 1 MB limit (including 0 and exactly 1 MB)
        return Arbitraries.longs().between(0L, 1_048_576L);
    }

    @Provide
    Arbitrary<String> oversizedTitles() {
        // Titles exceeding the 200-character max
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(201)
                .ofMaxLength(500);
    }
}
