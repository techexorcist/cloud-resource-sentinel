package com.cloudsentinel.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.service.AwsProfileService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * REST controller for the Profiles API group, handling AWS credential profile discovery and health checks.
 *
 * <p>Provides endpoints to list all configured AWS profiles (from {@code ~/.aws/config} and
 * {@code ~/.aws/credentials}) and to verify that a profile's credentials are valid by calling
 * STS GetCallerIdentity. Credential health checks classify failures into SSO expired,
 * unsupported auth methods, missing credentials, and generic errors with actionable fix suggestions.</p>
 */
@RestController
@Tag(name = "Profiles")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);
    private final AwsProfileService awsProfileService;
    private final boolean mockMode;

    public ProfileController(AwsProfileService awsProfileService,
                             org.springframework.core.env.Environment environment) {
        this.awsProfileService = awsProfileService;
        this.mockMode = java.util.List.of(environment.getActiveProfiles()).contains("mock-data");
    }

    /**
     * Lists all AWS profiles discovered from {@code ~/.aws/config} and {@code ~/.aws/credentials},
     * including SSO profiles.
     *
     * <p>GET {@code /profiles}</p>
     *
     * @return a map with a {@code profiles} key containing the list of profile name strings
     */
    @Operation(summary = "List AWS profiles", description = "Returns all AWS profiles from ~/.aws/config and ~/.aws/credentials, including SSO profiles.")
    @GetMapping("/profiles")
    public Map<String, Object> listProfiles() {
        var profiles = awsProfileService.listProfiles();
        if (mockMode) {
            var combined = new java.util.ArrayList<>(profiles);
            combined.addFirst("Demo");
            combined.addFirst("Demo-AI");
            profiles = combined;
        }
        return Map.of("profiles", profiles);
    }

    /**
     * Tests whether a profile's AWS credentials are valid by calling STS GetCallerIdentity.
     *
     * <p>GET {@code /profiles/check?profileName=...}</p>
     *
     * <p>On success, returns the profile name, status "valid", account ID, and ARN. On failure,
     * classifies the error into: SSO expired (with {@code aws sso login} fix), unsupported auth
     * method, missing credentials, or a generic error message.</p>
     *
     * @param profileName the AWS profile name to check (optional, defaults to "default")
     * @return a map with {@code profile}, {@code status} ("valid" or "error"), and on success:
     *         {@code account_id} and {@code arn}; on error: {@code error} and optionally {@code fix}
     */
    @Operation(summary = "Check credential health", description = "Tests if a profile's credentials are valid by calling STS GetCallerIdentity. Returns status and account ID or error message.")
    @GetMapping("/profiles/check")
    public Map<String, Object> checkCredentials(@RequestParam(required = false) String profileName) {
        Map<String, Object> result = new LinkedHashMap<>();
        String profile = (profileName == null || profileName.isBlank()) ? "default" : profileName;
        if (profile.length() > 128 || !profile.matches("^[a-zA-Z0-9._/@+\\-]+$")) {
            result.put("profile", profile.length() > 128 ? profile.substring(0, 128) + "..." : profile);
            result.put("status", "error");
            result.put("error", "Invalid profile name");
            return result;
        }
        result.put("profile", profile);
        try {
            var creds = ProfileCredentialsProvider.builder().profileName(profile).build();
            try (StsClient sts = ReadOnlyAwsClientFactory.build(StsClient.builder(), creds, Region.US_EAST_1)) {
                var identity = sts.getCallerIdentity();
                result.put("status", "valid");
                result.put("account_id", identity.account());
                result.put("arn", identity.arn());
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Credential check failed for profile '{}': {}", profile, msg);
            // Walk the full exception chain for classification
            String fullMsg = msg;
            Throwable cause = e;
            while (cause.getCause() != null) {
                cause = cause.getCause();
                if (cause.getMessage() != null) fullMsg += " " + cause.getMessage();
            }

            boolean isSsoExpired = fullMsg.contains("expired") || fullMsg.contains("SsoOidc")
                    || fullMsg.contains("InvalidGrant");
            boolean isNotFound = fullMsg.contains("Profile file contained no credentials")
                    || fullMsg.contains("Unable to load credentials");
            boolean isUnsupported = fullMsg.contains("LoginProfile") || fullMsg.contains("signin")
                    || fullMsg.contains("login_session") || fullMsg.contains("class path");

            result.put("status", "error");
            if (isSsoExpired) {
                result.put("error", "SSO session expired");
                result.put("fix", "Run: aws sso login --profile " + profile);
            } else if (isUnsupported) {
                result.put("error", "Unsupported auth method (login_session)");
                result.put("fix", "Use an SSO profile or enter credentials manually on the Analyse page");
            } else if (isNotFound) {
                result.put("error", "No credentials found for this profile");
                result.put("fix", "Check your ~/.aws/config and ~/.aws/credentials files");
            } else {
                String cleanMsg = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
                result.put("error", cleanMsg);
            }
        }
        return result;
    }
}
