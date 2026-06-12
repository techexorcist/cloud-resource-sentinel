package com.cloudsentinel.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring configuration class for application-wide caching using Caffeine.
 *
 * <p>This configuration sets up a {@link CaffeineCacheManager} that backs Spring's
 * {@code @Cacheable} abstraction with the high-performance Caffeine in-memory cache.
 * The primary use case is caching AWS pricing data retrieved from the AWS Pricing API,
 * which is expensive to fetch and changes infrequently.</p>
 *
 * <p>The cache is configured with a time-based eviction policy ({@code expireAfterWrite})
 * controlled by the {@code pricing.cache.ttl-hours} property (defaulting to 24 hours),
 * and a maximum entry count of 1,000 to bound memory usage. Pricing data can also be
 * manually refreshed via the {@code POST /pricing/refresh} endpoint without waiting for
 * cache expiration.</p>
 *
 * <p>Caching is enabled globally by the {@code @EnableCaching} annotation on the
 * {@link com.cloudsentinel.Application} class.</p>
 *
 * @see com.cloudsentinel.Application
 */
@Configuration
public class CacheConfig {

    /**
     * The time-to-live for cached entries, in hours. Configured via the
     * {@code pricing.cache.ttl-hours} application property, defaulting to 24 hours
     * if not specified.
     */
    @Value("${pricing.cache.ttl-hours:24}")
    private long ttlHours;

    /**
     * Creates and configures the Caffeine-backed cache manager for the application.
     *
     * <p>The returned manager provides a single named cache, {@code "pricing"}, configured with:
     * <ul>
     *   <li><b>expireAfterWrite</b>: entries are evicted after {@link #ttlHours} hours from
     *       the time they were written, ensuring stale pricing data is periodically refreshed</li>
     *   <li><b>maximumSize</b>: at most 1,000 entries are retained to prevent unbounded memory growth</li>
     * </ul>
     *
     * @return a {@link CaffeineCacheManager} instance configured for pricing data caching
     */
    @Bean
    public CaffeineCacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("pricing");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .maximumSize(1000));
        return manager;
    }
}
