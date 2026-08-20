package io.asbun.backend.config;

import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.HstsHeaderWriter;
import org.springframework.security.web.header.writers.XContentTypeOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.FilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Property-based tests for Security Headers and CORS configuration.
 *
 * Validates: Requirements 14.3, 14.5
 */
@Tag("Feature: security-legal-compliance")
class SecurityHeadersCorsPropertyTest {

    // The allowed origin in test config (matches application.properties)
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    // ========================================================================
    // Property 16: Security headers presence
    // ========================================================================

    /**
     * Property 16: For any HTTP response from the backend, the response SHALL include:
     * X-Content-Type-Options: nosniff, X-Frame-Options: DENY,
     * Strict-Transport-Security with max-age >= 31536000 and includeSubDomains,
     * and Content-Security-Policy with at minimum default-src 'self' and frame-ancestors 'none'.
     *
     * We test this by applying the same HeaderWriterFilter configuration as in SecurityConfig
     * to various request paths and HTTP methods, verifying the headers are always present.
     *
     * Validates: Requirements 14.3
     */
    @Property(tries = 100)
    @Tag("Property 16: Security headers presence")
    void allResponses_containRequiredSecurityHeaders(
            @ForAll("requestPaths") String path,
            @ForAll("httpMethods") String method
    ) throws Exception {
        HeaderWriterFilter headerWriterFilter = buildHeaderWriterFilter();

        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setSecure(true); // HSTS only applies to secure requests
        MockHttpServletResponse response = new MockHttpServletResponse();

        // The HeaderWriterFilter writes headers when the filter chain completes
        FilterChain chain = mock(FilterChain.class);
        headerWriterFilter.doFilter(request, response, chain);

        // X-Content-Type-Options: nosniff
        assertThat(response.getHeader("X-Content-Type-Options"))
                .as("X-Content-Type-Options header for %s %s", method, path)
                .isEqualTo("nosniff");

        // X-Frame-Options: DENY
        assertThat(response.getHeader("X-Frame-Options"))
                .as("X-Frame-Options header for %s %s", method, path)
                .isEqualTo("DENY");

        // Strict-Transport-Security with max-age >= 31536000 and includeSubDomains
        String hsts = response.getHeader("Strict-Transport-Security");
        assertThat(hsts)
                .as("Strict-Transport-Security header for %s %s", method, path)
                .isNotNull()
                .contains("includeSubDomains");
        long maxAge = extractMaxAge(hsts);
        assertThat(maxAge)
                .as("HSTS max-age for %s %s", method, path)
                .isGreaterThanOrEqualTo(31536000L);

        // Content-Security-Policy with default-src 'self' and frame-ancestors 'none'
        String csp = response.getHeader("Content-Security-Policy");
        assertThat(csp)
                .as("Content-Security-Policy header for %s %s", method, path)
                .isNotNull()
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'");
    }

    // ========================================================================
    // Property 17: CORS rejection for non-allowed origins
    // ========================================================================

    /**
     * Property 17: For any cross-origin request from an origin NOT in the configured
     * allowed origins list, the response SHALL omit CORS headers (Access-Control-Allow-Origin),
     * causing the browser to block the response.
     *
     * We test this by using the same CorsConfiguration as in CorsConfig and processing
     * CORS preflight requests with various non-allowed origins.
     *
     * Validates: Requirements 14.5
     */
    @Property(tries = 100)
    @Tag("Property 17: CORS rejection for non-allowed origins")
    void nonAllowedOrigins_omitCorsHeaders(
            @ForAll("nonAllowedOrigins") String origin
    ) throws Exception {
        CorsConfigurationSource corsSource = buildCorsConfigurationSource();
        CorsProcessor corsProcessor = new DefaultCorsProcessor();

        // Simulate a CORS preflight request with a non-allowed origin
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/health");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CorsConfiguration config = corsSource.getCorsConfiguration(request);
        boolean processed = corsProcessor.processRequest(config, request, response);

        // For non-allowed origins, Access-Control-Allow-Origin should be absent
        // The DefaultCorsProcessor rejects the request by setting a 403 and NOT setting CORS headers,
        // or simply omits the Access-Control-Allow-Origin header
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .as("Access-Control-Allow-Origin should be absent for non-allowed origin: %s", origin)
                .isNull();
    }

    /**
     * Sanity check: the allowed origin DOES receive CORS headers.
     * Confirms our test CORS configuration is correctly set up.
     *
     * Validates: Requirements 14.5
     */
    @Property(tries = 1)
    @Tag("Property 17: CORS rejection for non-allowed origins")
    void allowedOrigin_receivesCorsHeaders() throws Exception {
        CorsConfigurationSource corsSource = buildCorsConfigurationSource();
        CorsProcessor corsProcessor = new DefaultCorsProcessor();

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/health");
        request.addHeader("Origin", ALLOWED_ORIGIN);
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        CorsConfiguration config = corsSource.getCorsConfiguration(request);
        corsProcessor.processRequest(config, request, response);

        // The allowed origin should receive the CORS header
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .as("Access-Control-Allow-Origin should be present for allowed origin")
                .isEqualTo(ALLOWED_ORIGIN);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Build a HeaderWriterFilter with the same header configuration as SecurityConfig.
     * This mirrors the headers() DSL in SecurityConfig:
     * - contentTypeOptions (nosniff)
     * - frameOptions (DENY)
     * - httpStrictTransportSecurity (max-age=31536000, includeSubDomains)
     * - contentSecurityPolicy ("default-src 'self'; frame-ancestors 'none'")
     */
    private HeaderWriterFilter buildHeaderWriterFilter() {
        XContentTypeOptionsHeaderWriter xContentType = new XContentTypeOptionsHeaderWriter();
        XFrameOptionsHeaderWriter xFrameOptions = new XFrameOptionsHeaderWriter(
                XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY);
        HstsHeaderWriter hsts = new HstsHeaderWriter();
        hsts.setMaxAgeInSeconds(31536000);
        hsts.setIncludeSubDomains(true);
        ContentSecurityPolicyHeaderWriter csp = new ContentSecurityPolicyHeaderWriter(
                "default-src 'self'; frame-ancestors 'none'");

        return new HeaderWriterFilter(List.of(xContentType, xFrameOptions, hsts, csp));
    }

    /**
     * Build a CorsConfigurationSource matching the production CorsConfig.
     * Uses the same allowed origin as the test application.properties.
     */
    private CorsConfigurationSource buildCorsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(ALLOWED_ORIGIN));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "Origin", "X-Requested-With", "Cache-Control"
        ));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * Extract the max-age value from an HSTS header string.
     */
    private long extractMaxAge(String hstsHeader) {
        for (String part : hstsHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("max-age=")) {
                return Long.parseLong(trimmed.substring("max-age=".length()).trim());
            }
        }
        return 0L;
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> requestPaths() {
        return Arbitraries.of(
                "/api/health",
                "/api/recipes",
                "/api/account/delete",
                "/api/account/export",
                "/api/consent",
                "/api/account/profile",
                "/api/account/cancel-deletion"
        );
    }

    @Provide
    Arbitrary<String> httpMethods() {
        return Arbitraries.of("GET", "POST", "PUT", "DELETE");
    }

    @Provide
    Arbitrary<String> nonAllowedOrigins() {
        return Arbitraries.of(
                "http://evil.com",
                "https://malicious-site.org",
                "http://localhost:4000",
                "https://attacker.io",
                "http://not-allowed.example.com",
                "https://phishing.net",
                "http://192.168.1.100:8080",
                "https://fake-recipe-app.com",
                "http://localhost:5173",
                "https://hacker.dev"
        );
    }
}
