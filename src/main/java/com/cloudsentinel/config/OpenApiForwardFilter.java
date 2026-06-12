package com.cloudsentinel.config;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Servlet filter that intercepts SpringDoc OpenAPI specification responses and corrects
 * encoding issues caused by the application's global {@code SNAKE_CASE} Jackson configuration.
 *
 * <p><b>Problem:</b> SpringDoc internally serializes the OpenAPI spec as a {@code byte[]}
 * JSON value. When the application's primary {@link com.fasterxml.jackson.databind.ObjectMapper}
 * uses {@code SNAKE_CASE} naming, Jackson serializes this byte array as a Base64-encoded
 * string wrapped in double quotes (e.g., {@code "eyJvcGVuYXBpIj..."}). Swagger UI and other
 * consumers expect raw JSON, not a Base64 string, so the spec becomes unreadable.</p>
 *
 * <p><b>Solution:</b> This filter runs at the highest precedence and intercepts all requests
 * to {@code /v3/api-docs} and {@code /api-docs}. It captures the response body, detects if
 * it is a Base64-wrapped JSON string, decodes it back to the original JSON, and writes the
 * decoded content to the actual response. If the content is already valid JSON (not Base64),
 * it is passed through unchanged.</p>
 *
 * <p>This filter extends {@link OncePerRequestFilter} to guarantee it executes exactly once
 * per request, avoiding double-processing in forward/include scenarios.</p>
 *
 * @see OpenApiConfig
 * @see JacksonConfig
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenApiForwardFilter extends OncePerRequestFilter {

    /**
     * Intercepts the request/response pipeline to fix Base64-encoded OpenAPI spec responses.
     *
     * <p>For requests matching {@code /v3/api-docs} or {@code /api-docs}, the response body
     * is captured using a {@link ContentCaptureResponseWrapper}. If the captured content is
     * a Base64-wrapped JSON string (starts and ends with double quotes), it is decoded and
     * written as raw JSON. Otherwise, the original content is written through unchanged.
     * All other request paths are passed directly to the filter chain without interception.</p>
     *
     * @param request  the incoming HTTP request
     * @param response the HTTP response to write to
     * @param chain    the remaining filter chain to delegate to
     * @throws ServletException if a servlet processing error occurs
     * @throws IOException      if an I/O error occurs during filtering
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/v3/api-docs") || path.startsWith("/api-docs")) {
            // Capture the response
            var wrapper = new ContentCaptureResponseWrapper(response);
            chain.doFilter(request, wrapper);

            String content = wrapper.getCapturedContent();
            // If it's a base64-wrapped JSON string, decode it
            if (content != null && content.startsWith("\"") && content.endsWith("\"")) {
                try {
                    String base64 = content.substring(1, content.length() - 1);
                    byte[] decoded = java.util.Base64.getDecoder().decode(base64);
                    response.setContentType("application/json");
                    response.setContentLength(decoded.length);
                    response.getOutputStream().write(decoded);
                    return;
                } catch (Exception e) {
                    // Not base64, write original
                }
            }
            // Write original content
            if (content != null) {
                response.setContentType("application/json");
                byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                response.setContentLength(bytes.length);
                response.getOutputStream().write(bytes);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * An {@link HttpServletResponseWrapper} that captures response output into an in-memory
     * buffer instead of writing it directly to the client.
     *
     * <p>This wrapper redirects both {@link #getOutputStream()} and {@link #getWriter()}
     * to an internal {@link java.io.ByteArrayOutputStream}, allowing the filter to inspect
     * and potentially transform the response content before it is sent to the client.</p>
     */
    private static class ContentCaptureResponseWrapper extends HttpServletResponseWrapper {
        private final java.io.ByteArrayOutputStream capture = new java.io.ByteArrayOutputStream();
        private jakarta.servlet.ServletOutputStream output;
        private java.io.PrintWriter writer;

        /**
         * Constructs a new wrapper around the given response.
         *
         * @param response the original HTTP response to wrap
         */
        public ContentCaptureResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        /**
         * Returns a {@link jakarta.servlet.ServletOutputStream} that writes to the internal
         * capture buffer instead of the client socket.
         *
         * @return a capturing output stream
         * @throws IOException if an I/O error occurs
         */
        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
            if (output == null) {
                output = new jakarta.servlet.ServletOutputStream() {
                    @Override public void write(int b) { capture.write(b); }
                    @Override public void write(byte[] b, int off, int len) { capture.write(b, off, len); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(jakarta.servlet.WriteListener l) {}
                };
            }
            return output;
        }

        /**
         * Returns a {@link java.io.PrintWriter} that writes to the internal capture buffer
         * using UTF-8 encoding.
         *
         * @return a capturing print writer
         * @throws IOException if an I/O error occurs
         */
        @Override
        public java.io.PrintWriter getWriter() throws IOException {
            if (writer == null) {
                writer = new java.io.PrintWriter(new java.io.OutputStreamWriter(capture, java.nio.charset.StandardCharsets.UTF_8));
            }
            return writer;
        }

        /**
         * Flushes any buffered content in the writer to the capture buffer.
         *
         * @throws IOException if an I/O error occurs during flushing
         */
        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) writer.flush();
        }

        /**
         * Returns the captured response content as a UTF-8 string.
         *
         * <p>If a writer was used, it is flushed before reading the buffer to ensure
         * all content is captured.</p>
         *
         * @return the captured response body as a string
         */
        public String getCapturedContent() {
            if (writer != null) writer.flush();
            return capture.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
