package com.cloudsentinel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring configuration class for Cross-Origin Resource Sharing (CORS) policies.
 *
 * <p>This configuration allows the Thymeleaf-rendered frontend (served on
 * {@code localhost:8000}) to make API calls to the backend without being blocked by
 * browser same-origin policies. It is particularly relevant during local development
 * where the frontend may be accessed via both IPv4 ({@code http://localhost:8000})
 * and IPv6 ({@code http://[::1]:8000}) loopback addresses.</p>
 *
 * <p>The CORS policy is intentionally permissive for development convenience -- all
 * HTTP methods and headers are allowed on all paths. In a production deployment behind
 * a reverse proxy, CORS would typically be handled at the proxy layer instead.</p>
 */
@Configuration
public class CorsConfig {

    /**
     * Creates a {@link WebMvcConfigurer} that registers CORS mappings for all endpoints.
     *
     * <p>The configuration allows requests from the local development origins
     * ({@code http://localhost:8000} and {@code http://[::1]:8000}) with any HTTP method
     * and any request header. This covers all REST API and page navigation requests
     * from the browser-based frontend.</p>
     *
     * @return a {@link WebMvcConfigurer} with CORS mappings applied to all URL patterns
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                // For reverse-proxy deployments, override via CORS_ORIGINS env var
                String origins = System.getenv("CORS_ORIGINS");
                var mapping = registry.addMapping("/**")
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Accept", "X-Requested-With")
                        .maxAge(3600);
                if (origins != null && !origins.isBlank()) {
                    mapping.allowedOrigins(origins.split(","));
                } else {
                    mapping.allowedOrigins("http://localhost:8000", "http://[::1]:8000");
                }
            }
        };
    }
}
