package com.cloudsentinel.config;

import java.time.Duration;
import java.util.List;

import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;

/**
 * Centralized factory for building AWS SDK clients with read-only protection.
 *
 * Every AWS client in Cloud Resource Sentinel MUST be created through this
 * factory. It attaches {@link ReadOnlyInterceptor} to every client, which
 * blocks any mutating API calls (create, delete, modify, update, put, etc.)
 * at the SDK level before they reach the network.
 *
 * Usage:
 * <pre>
 *   try (Ec2Client ec2 = ReadOnlyAwsClientFactory.build(
 *           Ec2Client.builder(), creds, Region.of(region))) {
 *       // Only read operations will succeed
 *   }
 * </pre>
 *
 * Architecture tests enforce that no code outside this class calls
 * .builder() on AWS SDK clients directly.
 */
public final class ReadOnlyAwsClientFactory {

    private static final ReadOnlyInterceptor READ_ONLY_INTERCEPTOR = new ReadOnlyInterceptor();

    private static volatile int maxRetries = 1;
    private static volatile int apiCallTimeoutSeconds = 10;
    private static volatile int apiCallAttemptTimeoutSeconds = 5;

    private ReadOnlyAwsClientFactory() {}

    /**
     * Configures SDK timeout and retry settings. Called by {@link AwsClientConfig} at startup.
     *
     * @param retries              max retries per API call (0 = no retries, just 1 attempt)
     * @param callTimeoutSecs      total timeout for an API call including all retries
     * @param attemptTimeoutSecs   timeout for a single HTTP attempt
     */
    public static void configure(int retries, int callTimeoutSecs, int attemptTimeoutSecs) {
        maxRetries = retries;
        apiCallTimeoutSeconds = callTimeoutSecs;
        apiCallAttemptTimeoutSeconds = attemptTimeoutSecs;
    }

    /**
     * Build any AWS SDK client with read-only guardrails.
     *
     * @param builder the SDK client builder (e.g. Ec2Client.builder())
     * @param creds   AWS credentials provider
     * @param region  AWS region
     * @param <B>     builder type
     * @param <C>     client type
     * @return a fully configured, read-only client
     */
    @SuppressWarnings("unchecked")
    public static <B extends AwsClientBuilder<B, C>, C> C build(
            B builder, AwsCredentialsProvider creds, Region region) {
        return builder
                .credentialsProvider(creds)
                .region(region)
                .overrideConfiguration(buildOverrideConfig())
                .build();
    }

    /**
     * Build a client with region only (no explicit credentials — uses default chain).
     * Used for non-scan clients like Pricing API.
     */
    @SuppressWarnings("unchecked")
    public static <B extends AwsClientBuilder<B, C>, C> C build(B builder, Region region) {
        return builder
                .region(region)
                .overrideConfiguration(buildOverrideConfig())
                .build();
    }

    private static ClientOverrideConfiguration buildOverrideConfig() {
        return ClientOverrideConfiguration.builder()
                .executionInterceptors(List.of(READ_ONLY_INTERCEPTOR))
                .retryPolicy(RetryPolicy.builder().numRetries(maxRetries).build())
                .apiCallTimeout(Duration.ofSeconds(apiCallTimeoutSeconds))
                .apiCallAttemptTimeout(Duration.ofSeconds(apiCallAttemptTimeoutSeconds))
                .build();
    }
}
