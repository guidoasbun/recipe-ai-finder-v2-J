package io.asbun.backend.config;

import io.asbun.backend.model.enums.RateLimitCategory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.jqwik.api.*;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for RateLimitFilter.
 *
 * Validates: Requirements 9.1, 9.2, 9.3, 9.4, 9.5
 */
@Tag("Feature: security-legal-compliance")
class RateLimitFilterPropertyTest {

    // ========================================================================
    // Property 13: Rate limiter per-user isolation
    // ========================================================================

    /**
     * Property 13a: For two distinct authenticated users, user A exhausting their
     * rate limit in a category SHALL NOT affect user B's allowance in that category.
     *
     * Validates: Requirements 9.4
     */
    @Property(tries = 100)
    @Tag("Property 13: Rate limiter per-user isolation")
    void exhaustingUserA_doesNotAffectUserB(
            @ForAll("distinctUserPairs") String[] userPair,
            @ForAll("rateLimitCategories") RateLimitCategory category
    ) throws Exception {
        JwtDecoder jwtDecoder = mock(JwtDecoder.class);
        RateLimitFilter filter = new RateLimitFilter(jwtDecoder);

        String userA = userPair[0];
        String userB = userPair[1];
        String path = pathForCategory(category);
        int threshold = thresholdForCategory(category);

        // Setup JWT decoding for user A
        setupJwtDecoder(jwtDecoder, "token-" + userA, userA);
        setupJwtDecoder(jwtDecoder, "token-" + userB, userB);

        // Exhaust user A's rate limit
        for (int i = 0; i < threshold; i++) {
            HttpServletRequest request = createAuthenticatedRequest("token-" + userA, path);
            HttpServletResponse response = createMockResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(request, response, chain);
        }

        // Verify user A is now blocked (threshold + 1)
        HttpServletRequest blockedRequest = createAuthenticatedRequest("token-" + userA, path);
        HttpServletResponse blockedResponse = createMockResponse();
        FilterChain blockedChain = mock(FilterChain.class);
        filter.doFilterInternal(blockedRequest, blockedResponse, blockedChain);
        verify(blockedResponse).setStatus(429);

        // Verify user B can still make a request in the same category
        HttpServletRequest requestB = createAuthenticatedRequest("token-" + userB, path);
        HttpServletResponse responseB = createMockResponse();
        FilterChain chainB = mock(FilterChain.class);
        filter.doFilterInternal(requestB, responseB, chainB);

        // User B's request should pass through (filterChain.doFilter called)
        verify(chainB).doFilter(requestB, responseB);
        verify(responseB, never()).setStatus(429);
    }

    /**
     * Property 13b: For a single user, exhausting one category SHALL NOT block
     * requests to a different category.
     *
     * Validates: Requirements 9.1, 9.2, 9.4, 9.5
     */
    @Property(tries = 100)
    @Tag("Property 13: Rate limiter per-user isolation")
    void exhaustingOneCategory_doesNotBlockOtherCategory(
            @ForAll("userIds") String userId,
            @ForAll("distinctCategoryPairs") RateLimitCategory[] categoryPair
    ) throws Exception {
        JwtDecoder jwtDecoder = mock(JwtDecoder.class);
        RateLimitFilter filter = new RateLimitFilter(jwtDecoder);

        RateLimitCategory exhaustedCategory = categoryPair[0];
        RateLimitCategory otherCategory = categoryPair[1];

        String exhaustedPath = pathForCategory(exhaustedCategory);
        String otherPath = pathForCategory(otherCategory);
        int threshold = thresholdForCategory(exhaustedCategory);

        setupJwtDecoder(jwtDecoder, "token-" + userId, userId);

        // Exhaust the first category
        for (int i = 0; i < threshold; i++) {
            HttpServletRequest request = createAuthenticatedRequest("token-" + userId, exhaustedPath);
            HttpServletResponse response = createMockResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(request, response, chain);
        }

        // Verify the exhausted category is blocked
        HttpServletRequest blockedRequest = createAuthenticatedRequest("token-" + userId, exhaustedPath);
        HttpServletResponse blockedResponse = createMockResponse();
        FilterChain blockedChain = mock(FilterChain.class);
        filter.doFilterInternal(blockedRequest, blockedResponse, blockedChain);
        verify(blockedResponse).setStatus(429);

        // Verify the other category still works
        HttpServletRequest otherRequest = createAuthenticatedRequest("token-" + userId, otherPath);
        HttpServletResponse otherResponse = createMockResponse();
        FilterChain otherChain = mock(FilterChain.class);
        filter.doFilterInternal(otherRequest, otherResponse, otherChain);

        verify(otherChain).doFilter(otherRequest, otherResponse);
        verify(otherResponse, never()).setStatus(429);
    }

    // ========================================================================
    // Property 14: Rate limiter threshold enforcement and response format
    // ========================================================================

    /**
     * Property 14a: Requests within the threshold SHALL succeed (filterChain.doFilter called),
     * and the (threshold+1)th request SHALL receive HTTP 429 with Retry-After header.
     *
     * Validates: Requirements 9.1, 9.2, 9.3, 9.5
     */
    @Property(tries = 100)
    @Tag("Property 14: Rate limiter threshold enforcement and response format")
    void thresholdEnforcement_blocksAfterLimit(
            @ForAll("userIds") String userId,
            @ForAll("rateLimitCategories") RateLimitCategory category
    ) throws Exception {
        JwtDecoder jwtDecoder = mock(JwtDecoder.class);
        RateLimitFilter filter = new RateLimitFilter(jwtDecoder);

        String path = pathForCategory(category);
        int threshold = thresholdForCategory(category);

        setupJwtDecoder(jwtDecoder, "token-" + userId, userId);

        // Make exactly threshold requests — all should succeed
        for (int i = 0; i < threshold; i++) {
            HttpServletRequest request = createAuthenticatedRequest("token-" + userId, path);
            HttpServletResponse response = createMockResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(request, response, chain);
            verify(chain).doFilter(request, response);
            verify(response, never()).setStatus(429);
        }

        // The (threshold+1)th request should be rejected with 429
        HttpServletRequest exceededRequest = createAuthenticatedRequest("token-" + userId, path);
        HttpServletResponse exceededResponse = createMockResponse();
        FilterChain exceededChain = mock(FilterChain.class);
        filter.doFilterInternal(exceededRequest, exceededResponse, exceededChain);

        verify(exceededResponse).setStatus(429);
        verify(exceededChain, never()).doFilter(exceededRequest, exceededResponse);
    }

    /**
     * Property 14b: When rate limit is exceeded, the response contains a Retry-After header
     * with a positive integer representing seconds until the next allowed request.
     *
     * Validates: Requirements 9.3
     */
    @Property(tries = 100)
    @Tag("Property 14: Rate limiter threshold enforcement and response format")
    void exceededLimit_includesRetryAfterHeader(
            @ForAll("userIds") String userId,
            @ForAll("rateLimitCategories") RateLimitCategory category
    ) throws Exception {
        JwtDecoder jwtDecoder = mock(JwtDecoder.class);
        RateLimitFilter filter = new RateLimitFilter(jwtDecoder);

        String path = pathForCategory(category);
        int threshold = thresholdForCategory(category);

        setupJwtDecoder(jwtDecoder, "token-" + userId, userId);

        // Exhaust the bucket
        for (int i = 0; i < threshold; i++) {
            HttpServletRequest request = createAuthenticatedRequest("token-" + userId, path);
            HttpServletResponse response = createMockResponse();
            FilterChain chain = mock(FilterChain.class);
            filter.doFilterInternal(request, response, chain);
        }

        // Capture the Retry-After header on the exceeded request
        HttpServletRequest exceededRequest = createAuthenticatedRequest("token-" + userId, path);
        HttpServletResponse exceededResponse = createMockResponse();
        FilterChain exceededChain = mock(FilterChain.class);

        ArgumentCaptor<String> headerNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> headerValueCaptor = ArgumentCaptor.forClass(String.class);

        filter.doFilterInternal(exceededRequest, exceededResponse, exceededChain);

        verify(exceededResponse).setHeader(headerNameCaptor.capture(), headerValueCaptor.capture());

        assertThat(headerNameCaptor.getValue()).isEqualTo("Retry-After");
        String retryAfterValue = headerValueCaptor.getValue();
        int retryAfterSeconds = Integer.parseInt(retryAfterValue);
        assertThat(retryAfterSeconds).isPositive();
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void setupJwtDecoder(JwtDecoder jwtDecoder, String token, String userId) {
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "RS256")
                .claim("sub", userId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);
    }

    private HttpServletRequest createAuthenticatedRequest(String token, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        return request;
    }

    private HttpServletResponse createMockResponse() throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(writer);
        return response;
    }

    private String pathForCategory(RateLimitCategory category) {
        return switch (category) {
            case GENERAL -> "/api/recipes";
            case DELETION -> "/api/account/delete";
            case EXPORT -> "/api/account/export";
        };
    }

    private int thresholdForCategory(RateLimitCategory category) {
        return switch (category) {
            case GENERAL -> 60;
            case DELETION -> 5;
            case EXPORT -> 1;
        };
    }

    // ========================================================================
    // Providers
    // ========================================================================

    @Provide
    Arbitrary<String> userIds() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(5)
                .ofMaxLength(20);
    }

    @Provide
    Arbitrary<String[]> distinctUserPairs() {
        return Arbitraries.strings()
                .alpha()
                .numeric()
                .ofMinLength(5)
                .ofMaxLength(20)
                .list()
                .ofSize(2)
                .filter(list -> !list.get(0).equals(list.get(1)))
                .map(list -> new String[]{list.get(0), list.get(1)});
    }

    @Provide
    Arbitrary<RateLimitCategory> rateLimitCategories() {
        return Arbitraries.of(RateLimitCategory.values());
    }

    @Provide
    Arbitrary<RateLimitCategory[]> distinctCategoryPairs() {
        return Arbitraries.of(RateLimitCategory.values())
                .list()
                .ofSize(2)
                .filter(list -> list.get(0) != list.get(1))
                .map(list -> new RateLimitCategory[]{list.get(0), list.get(1)});
    }
}
