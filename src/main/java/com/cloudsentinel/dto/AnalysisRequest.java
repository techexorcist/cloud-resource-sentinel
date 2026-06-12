package com.cloudsentinel.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object representing a request to perform a cloud resource analysis scan.
 *
 * <p>This record is the primary input payload for the {@code POST /analyse} and {@code POST /analyse/batch}
 * API endpoints. It specifies which AWS account to scan (via either a named credential profile or
 * explicit static credentials), which regions to scan, whether to apply AI-powered filtering, and
 * which AI provider/model to use for analysis.</p>
 *
 * <p>Authentication can be provided in two ways:
 * <ul>
 *   <li><b>Profile-based:</b> Set {@code profileName} to reference a locally configured AWS credential profile.</li>
 *   <li><b>Static credentials:</b> Provide an {@link AwsCredentialsDto} with access key, secret key, and
 *       optionally a session token.</li>
 * </ul>
 * The {@link #validate()} method enforces that at least one authentication method is provided and
 * that static credentials are complete if used.</p>
 *
 * <p>Serialized from JSON using the global Jackson {@code SNAKE_CASE} naming strategy. Explicit
 * {@code @JsonProperty} annotations are used on fields whose names differ from the snake_case
 * convention to ensure correct deserialization (e.g., {@code profile_name}, {@code enable_ai_filter}).</p>
 *
 * <p>As a Java {@code record}, instances are immutable once constructed.</p>
 *
 * @param credentials   optional static AWS credentials for authentication; mutually exclusive with {@code profileName}
 * @param profileName   the name of a locally configured AWS credential profile to use for authentication
 * @param regions       the list of AWS region codes to scan (e.g., "us-east-1", "eu-west-1"); if {@code null}
 *                      or empty, all 30 supported regions are scanned
 * @param enableAiFilter whether to enable AI-powered filtering of idle resource candidates
 * @param aiProvider    the preferred AI provider ("ollama" or "bedrock"); if {@code null} or blank,
 *                      the system auto-selects the best available provider
 * @param aiModel       the specific AI model to use for analysis; if {@code null}, the provider's default is used
 * @param scanCategory  the category of scanners to run: "cost_idle", "security_governance", or "full"
 *
 * @see AwsCredentialsDto
 * @see AnalysisResponse
 */
public record AnalysisRequest(
        AwsCredentialsDto credentials,
        @JsonProperty("profile_name") String profileName,
        List<String> regions,
        @JsonProperty("enable_ai_filter") Boolean enableAiFilter,
        @JsonProperty("ai_provider") String aiProvider,
        @JsonProperty("ai_model") String aiModel,
        @JsonProperty("scan_category") String scanCategory
) {
    /**
     * Checks whether AI filtering is enabled for this analysis request.
     *
     * <p>Uses {@link Boolean#TRUE} equality to safely handle {@code null} values,
     * returning {@code false} when {@code enableAiFilter} is {@code null} or {@code false}.</p>
     *
     * @return {@code true} if AI filtering is explicitly enabled, {@code false} otherwise
     */
    public boolean isAiFilterEnabled() {
        return Boolean.TRUE.equals(enableAiFilter);
    }

    /**
     * Resolves the AI provider to use for this analysis request.
     *
     * <p>Returns the explicitly set provider if it is non-null and non-blank, or {@code null}
     * if no provider was specified. When {@code null} is returned, the caller should use
     * {@code AiAnalysisService} to determine the best available provider at runtime.</p>
     *
     * @return the AI provider name if explicitly specified, or {@code null} if auto-selection should be used
     */
    public String resolvedAiProvider() {
        return (aiProvider == null || aiProvider.isBlank()) ? null : aiProvider;
    }

    /**
     * Validates this analysis request, ensuring that the required fields are present and consistent.
     *
     * <p>The following validations are performed:
     * <ol>
     *   <li>At least one of {@code profileName} or {@code credentials} must be provided.</li>
     *   <li>If using static credentials (no profile name), both {@code accessKeyId} and
     *       {@code secretAccessKey} must be non-null and non-blank.</li>
     *   <li>The regions list, if provided, must not exceed 50 entries.</li>
     * </ol>
     *
     * @throws IllegalArgumentException if any validation rule is violated
     */
    /** Maximum allowed length for a profile name. */
    private static final int MAX_PROFILE_NAME_LENGTH = 128;
    /** Pattern for valid AWS profile names: alphanumeric, hyphens, underscores, dots, and forward slashes. */
    private static final java.util.regex.Pattern PROFILE_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9._/@+-]+$");
    /** Pattern for valid AWS region codes (e.g., us-east-1, ap-southeast-2). */
    private static final java.util.regex.Pattern REGION_PATTERN =
            java.util.regex.Pattern.compile("^[a-z]{2}(-[a-z]+-\\d+|global)$");

    /**
     * Factory method for creating a profile-based analysis request (used by scheduled scans).
     */
    public static AnalysisRequest ofProfile(String profile, List<String> regions, String category,
                                            boolean aiEnabled, String aiProvider) {
        return new AnalysisRequest(null, profile, regions, aiEnabled, aiProvider, null, category);
    }

    public void validate() {
        if ((profileName == null || profileName.isBlank()) && credentials == null) {
            throw new IllegalArgumentException("Either profileName or credentials must be provided");
        }
        if (profileName != null && !profileName.isBlank()) {
            if (profileName.length() > MAX_PROFILE_NAME_LENGTH) {
                throw new IllegalArgumentException("profileName exceeds maximum length of " + MAX_PROFILE_NAME_LENGTH);
            }
            if (!PROFILE_NAME_PATTERN.matcher(profileName).matches()) {
                throw new IllegalArgumentException("profileName contains invalid characters");
            }
        }
        if ((profileName == null || profileName.isBlank()) && credentials != null) {
            if (credentials.accessKeyId() == null || credentials.accessKeyId().isBlank()) {
                throw new IllegalArgumentException("accessKeyId is required when using static credentials");
            }
            if (credentials.secretAccessKey() == null || credentials.secretAccessKey().isBlank()) {
                throw new IllegalArgumentException("secretAccessKey is required when using static credentials");
            }
        }
        if (regions != null && regions.size() > 50) {
            throw new IllegalArgumentException("regions list exceeds maximum of 50");
        }
        if (regions != null) {
            for (String region : regions) {
                if (region == null || !REGION_PATTERN.matcher(region).matches()) {
                    throw new IllegalArgumentException("Invalid AWS region code: " + region);
                }
            }
        }
    }
}
