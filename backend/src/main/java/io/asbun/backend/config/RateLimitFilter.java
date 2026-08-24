package io.asbun.backend.config;

import io.asbun.backend.model.enums.RateLimitCategory;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiting filter that enforces per-user token bucket limits using Bucket4j.
 * <p>
 * Categories:
 * - GENERAL: 60 requests/min (1 token/second refill)
 * - DELETION: 5 requests/hour (1 token/720 seconds refill)
 * - EXPORT: 1 request/hour (1 token/3600 seconds refill)
 * - Unauthenticated (IP-based): 20 requests/min (1 token/3 seconds refill)
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final JwtDecoder jwtDecoder;

    // Per-user buckets: userId -> (category -> bucket)
    private final Map<String, Map<RateLimitCategory, Bucket>> userBuckets = new ConcurrentHashMap<>();

    // Per-IP buckets for unauthenticated requests
    private final Map<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip rate limiting for health endpoint
        if ("/api/health".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = extractUserIdFromRequest(request);

        if (userId != null) {
            // Authenticated user - apply per-user rate limiting
            RateLimitCategory category = resolveCategory(path);
            Bucket bucket = resolveUserBucket(userId, category);

            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long waitSeconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(waitSeconds));
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":429,\"message\":\"Rate limit exceeded. Try again in " + waitSeconds + " seconds.\"}");
                return;
            }
        } else {
            // Unauthenticated request - apply per-IP rate limiting
            String clientIp = getClientIp(request);
            Bucket bucket = resolveIpBucket(clientIp);

            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                long waitSeconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(waitSeconds));
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":429,\"message\":\"Rate limit exceeded. Try again in " + waitSeconds + " seconds.\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract userId (sub claim) from the JWT in the Authorization header.
     * Returns null if extraction fails (no header, invalid token, etc.).
     */
    private String extractUserIdFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }

        String token = authHeader.substring(7);
        try {
            Jwt jwt = jwtDecoder.decode(token);
            return jwt.getSubject();
        } catch (JwtException e) {
            log.debug("Failed to decode JWT for rate limiting: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Map endpoint paths to rate limit categories.
     */
    RateLimitCategory resolveCategory(String path) {
        if (path.startsWith("/api/account/delete") || path.startsWith("/api/account/cancel-deletion")) {
            return RateLimitCategory.DELETION;
        }
        if (path.startsWith("/api/account/export") && !path.startsWith("/api/account/export/status")) {
            return RateLimitCategory.EXPORT;
        }
        return RateLimitCategory.GENERAL;
    }

    /**
     * Get or create a token bucket for a given user and category.
     */
    private Bucket resolveUserBucket(String userId, RateLimitCategory category) {
        return userBuckets
                .computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(category, k -> createBucketForCategory(k));
    }

    /**
     * Get or create a token bucket for a given IP address (unauthenticated requests).
     */
    private Bucket resolveIpBucket(String ip) {
        return ipBuckets.computeIfAbsent(ip, k -> createIpBucket());
    }

    /**
     * Create a token bucket based on the rate limit category.
     */
    private Bucket createBucketForCategory(RateLimitCategory category) {
        return switch (category) {
            case GENERAL -> Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(60)
                            .refillGreedy(60, Duration.ofMinutes(1))
                            .build())
                    .build();
            case DELETION -> Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(5)
                            .refillGreedy(5, Duration.ofHours(1))
                            .build())
                    .build();
            case EXPORT -> Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(1)
                            .refillGreedy(1, Duration.ofHours(1))
                            .build())
                    .build();
        };
    }

    /**
     * Create a token bucket for unauthenticated IP-based rate limiting.
     * 20 requests per minute (1 token every 3 seconds).
     */
    private Bucket createIpBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(20)
                        .refillGreedy(20, Duration.ofMinutes(1))
                        .build())
                .build();
    }

    /**
     * Extract the client IP address from the request.
     * <p>
     * Uses the rightmost IP from X-Forwarded-For, which is the address observed by the
     * last trusted proxy (e.g. the ALB). This prevents clients from spoofing arbitrary IPs
     * by prepending values to the header. Falls back to the remote address when the header
     * is absent.
     * <p>
     * The returned value is truncated to 45 characters (max length of an IPv6 address)
     * to prevent unbounded key growth in ipBuckets.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // Take the rightmost (last) IP — the one appended by the trusted proxy (ALB)
            String[] ips = xForwardedFor.split(",");
            String clientIp = ips[ips.length - 1].trim();
            return sanitizeIp(clientIp);
        }
        return sanitizeIp(request.getRemoteAddr());
    }

    /**
     * Truncates the IP string to prevent unbounded map key sizes from malformed headers.
     */
    private String sanitizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        // Max valid IP length is 45 chars (IPv6 mapped IPv4: "::ffff:255.255.255.255" or full IPv6)
        return ip.length() > 45 ? ip.substring(0, 45) : ip;
    }
}
