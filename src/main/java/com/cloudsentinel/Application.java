package com.cloudsentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main entry point for the Cloud Resource Sentinel application.
 *
 * <p>Cloud Resource Sentinel is an AI-powered cloud idle resource finder and cost optimizer.
 * It scans AWS accounts across 30 regions to identify idle or underutilized resources,
 * estimates costs using per-region pricing data, and provides AI-powered recommendations
 * for optimization via Ollama (local) or AWS Bedrock (Claude).</p>
 *
 * <p>This class bootstraps the Spring Boot application context and enables caching support
 * (backed by Caffeine) which is used primarily for pricing data with a configurable TTL.
 * The application uses Java 21 virtual threads for parallel region and resource type scanning,
 * server-rendered Thymeleaf templates with Bootstrap 5 for the frontend, and a read-only
 * security model that prevents any mutating AWS API calls.</p>
 *
 * @see com.cloudsentinel.config.CacheConfig
 * @see com.cloudsentinel.config.ReadOnlyInterceptor
 */
@SpringBootApplication
@EnableCaching
@org.springframework.scheduling.annotation.EnableScheduling
public class Application {

    /**
     * Launches the Cloud Resource Sentinel Spring Boot application.
     *
     * <p>Initializes the Spring application context, auto-discovers all configuration classes,
     * controllers, services, and scanners under the {@code com.cloudsentinel} package, and
     * starts the embedded web server.</p>
     *
     * @param args command-line arguments passed to the application; supports standard
     *             Spring Boot arguments such as {@code --server.port} and {@code --spring.profiles.active}
     */
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
