package com.cloudsentinel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * Data Transfer Object representing static AWS credentials for authenticating API calls.
 *
 * <p>This record provides an alternative to profile-based authentication in {@link AnalysisRequest}.
 * When users cannot or prefer not to use locally configured AWS credential profiles, they can supply
 * an access key ID, secret access key, and optionally a session token (for temporary/assumed-role
 * credentials) directly in the API request.</p>
 *
 * <p>The compact canonical constructor applies a default region of {@code "us-east-1"} when the
 * {@code region} field is {@code null} or blank, ensuring that a valid default region is always
 * available for AWS client construction.</p>
 *
 * <p>Serialized from JSON using explicit {@code @JsonProperty} annotations for fields whose names
 * use underscores (e.g., {@code access_key_id}, {@code secret_access_key}, {@code session_token}).
 * The {@code @NotBlank} validation annotations on required fields are enforced during
 * {@link AnalysisRequest#validate()} rather than via Bean Validation framework auto-validation.</p>
 *
 * <p>As a Java {@code record}, instances are immutable once constructed (after the compact
 * constructor's region defaulting logic has been applied).</p>
 *
 * @param accessKeyId    the AWS access key ID; must not be blank when static credentials are used
 * @param secretAccessKey the AWS secret access key; must not be blank when static credentials are used
 * @param sessionToken   an optional AWS session token for temporary credentials (e.g., from STS AssumeRole);
 *                       may be {@code null} for long-lived IAM user credentials
 * @param region         the default AWS region to use; defaults to {@code "us-east-1"} if {@code null} or blank
 *
 * @see AnalysisRequest
 */
public record AwsCredentialsDto(
        @NotBlank @JsonProperty("access_key_id") String accessKeyId,
        @NotBlank @JsonProperty("secret_access_key") String secretAccessKey,
        @JsonProperty("session_token") String sessionToken,
        String region
) {
    /**
     * Compact canonical constructor that applies a default region when none is provided.
     *
     * <p>If the {@code region} parameter is {@code null} or blank, it is replaced with
     * {@code "us-east-1"} to ensure a valid region is always available for AWS client construction.</p>
     */
    public AwsCredentialsDto {
        region = (region == null || region.isBlank()) ? "us-east-1" : region;
    }
}
