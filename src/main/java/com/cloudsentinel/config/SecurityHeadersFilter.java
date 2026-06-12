package com.cloudsentinel.config;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filter that adds security headers to all responses and provides basic rate limiting
 * and request logging for non-actuator paths.
 *
 * <p>Security headers: Content-Security-Policy, X-Content-Type-Options, X-Frame-Options.
 * Rate limiting: 30 POST /analyse requests per minute per client IP.
 * Request logging: logs method, path, status, and duration at INFO level.</p>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityHeadersFilter.class);

    /** Rate limit: max POST /analyse requests per minute per IP. */
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    private final ConcurrentHashMap<String, AtomicInteger> rateCounts = new ConcurrentHashMap<>();
    private volatile long rateWindowStart = System.currentTimeMillis();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Security headers
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; " +
                "script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; " +
                "img-src 'self' data:; " +
                "font-src 'self' https://cdn.jsdelivr.net;");

        // Rate limiting for scan submission endpoints
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && (path.equals("/analyse") || path.equals("/analyse/batch"))) {
            if (!checkRateLimit(request)) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write("{\"detail\":\"Rate limit exceeded. Max " + RATE_LIMIT_PER_MINUTE + " requests/min.\"}");
                return;
            }
        }

        // Request logging (skip actuator and static resources)
        boolean shouldLog = !path.startsWith("/actuator") && !path.startsWith("/css")
                && !path.startsWith("/js") && !path.startsWith("/img");

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (shouldLog) {
                long duration = System.currentTimeMillis() - start;
                log.info("{} {} {} {}ms", request.getMethod(), path, response.getStatus(), duration);
            }
        }
    }

    private boolean checkRateLimit(HttpServletRequest request) {
        long now = System.currentTimeMillis();
        // Reset window every minute
        if (now - rateWindowStart > 60_000) {
            rateCounts.clear();
            rateWindowStart = now;
        }
        // Respect X-Forwarded-For when behind a reverse proxy (nginx, ALB, Cloudflare)
        String forwarded = request.getHeader("X-Forwarded-For");
        String clientIp = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        AtomicInteger count = rateCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        return count.incrementAndGet() <= RATE_LIMIT_PER_MINUTE;
    }
}
