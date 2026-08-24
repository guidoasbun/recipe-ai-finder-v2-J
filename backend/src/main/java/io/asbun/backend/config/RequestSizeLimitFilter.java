package io.asbun.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that rejects requests exceeding the maximum allowed body size (1 MB).
 * <p>
 * For requests with a declared Content-Length, rejects immediately with HTTP 413.
 * For chunked or streaming requests (Content-Length = -1), wraps the input stream
 * with a byte-counting decorator that throws an IOException once the limit is exceeded,
 * which Spring translates into an appropriate error response.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final long MAX_REQUEST_SIZE = 1_048_576L; // 1 MB

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();

        // Fast reject when Content-Length is declared and over limit
        if (contentLength > MAX_REQUEST_SIZE) {
            rejectPayloadTooLarge(response);
            return;
        }

        // For chunked/streaming requests (contentLength == -1), wrap with a counting stream
        if (contentLength == -1) {
            HttpServletRequest wrappedRequest = new SizeLimitedRequestWrapper(request, MAX_REQUEST_SIZE);
            try {
                filterChain.doFilter(wrappedRequest, response);
            } catch (SizeLimitExceededException e) {
                if (!response.isCommitted()) {
                    response.reset();
                    rejectPayloadTooLarge(response);
                }
            }
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void rejectPayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"status\":413,\"message\":\"Payload Too Large: request body exceeds 1 MB limit\"}"
        );
    }

    /**
     * Request wrapper that replaces the input stream with a byte-counting decorator.
     */
    private static class SizeLimitedRequestWrapper extends HttpServletRequestWrapper {

        private final long maxSize;

        SizeLimitedRequestWrapper(HttpServletRequest request, long maxSize) {
            super(request);
            this.maxSize = maxSize;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new SizeLimitedServletInputStream(super.getInputStream(), maxSize);
        }
    }

    /**
     * ServletInputStream decorator that tracks bytes read and throws
     * SizeLimitExceededException once the configured limit is exceeded.
     */
    private static class SizeLimitedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxSize;
        private long bytesRead;

        SizeLimitedServletInputStream(ServletInputStream delegate, long maxSize) {
            this.delegate = delegate;
            this.maxSize = maxSize;
            this.bytesRead = 0;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
                checkLimit();
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int count = delegate.read(buf, off, len);
            if (count > 0) {
                bytesRead += count;
                checkLimit();
            }
            return count;
        }

        private void checkLimit() throws SizeLimitExceededException {
            if (bytesRead > maxSize) {
                throw new SizeLimitExceededException(
                        "Request body exceeds " + maxSize + " bytes limit");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * Sentinel exception used to signal size limit violations from within the input stream
     * back up to the filter for proper HTTP 413 handling.
     */
    static class SizeLimitExceededException extends IOException {
        SizeLimitExceededException(String message) {
            super(message);
        }
    }
}
