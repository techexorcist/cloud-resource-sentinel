package com.cloudsentinel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Configures AWS SDK client timeouts and retry policy from application properties.
 * Values are applied to {@link ReadOnlyAwsClientFactory} at startup.
 */
@Configuration
public class AwsClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsClientConfig.class);

    @Value("${aws.sdk.max-retries:1}")
    private int maxRetries;

    @Value("${aws.sdk.api-call-timeout-seconds:10}")
    private int apiCallTimeoutSeconds;

    @Value("${aws.sdk.api-call-attempt-timeout-seconds:5}")
    private int apiCallAttemptTimeoutSeconds;

    @PostConstruct
    void init() {
        ReadOnlyAwsClientFactory.configure(maxRetries, apiCallTimeoutSeconds, apiCallAttemptTimeoutSeconds);
        log.info("AWS SDK config: maxRetries={}, apiCallTimeout={}s, attemptTimeout={}s",
                maxRetries, apiCallTimeoutSeconds, apiCallAttemptTimeoutSeconds);
    }
}
