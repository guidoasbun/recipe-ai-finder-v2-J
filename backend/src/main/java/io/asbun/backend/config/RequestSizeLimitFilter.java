package io.asbun.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that rejects requests exceeding the maximum allowed body size (1 MB).
 * Returns HTTP 413 (Payload Too Large) when Content-Length header indicates
 * the request body exceeds the limit.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_REQUEST_SIZE = 1_048_576L; // 1 MB

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_SIZE) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                "{\"status\":413,\"message\":\"Payload Too Large: request body exceeds 1 MB limit\"}"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
