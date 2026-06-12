package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedRequest;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedResponse;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListMfaDevicesRequest;
import software.amazon.awssdk.services.iam.model.Role;
import software.amazon.awssdk.services.iam.model.User;

/**
 * Scans IAM users and roles for security hygiene issues.
 *
 * <p>Checks MFA status, access key age, last login, and last role usage. Skips
 * AWS service-linked roles. Delegates to {@link RecommendationEngine#getIamUserRecommendation}
 * and {@link RecommendationEngine#getIamRoleRecommendation} for classification.
 * Runs as a global scanner.</p>
 */
@Component
public class IamScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(IamScanner.class);
    private final RecommendationEngine engine;

    public IamScanner(RecommendationEngine engine) {
        this.engine = engine;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans IAM users and roles globally (AWS_GLOBAL region).
     *
     * <p>Calls {@code listUsersPaginator} and {@code listRolesPaginator}. Skips
     * AWS service-linked and service roles. Errors on individual users/roles are
     * logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan (ignored; uses AWS_GLOBAL)
     * @return list of discovered IAM users and roles with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var iam = ReadOnlyAwsClientFactory.build(IamClient.builder(), creds, Region.AWS_GLOBAL)) {

            iam.listUsersPaginator().users().forEach(user -> {
                try {
                    results.add(buildUserDto(iam, user));
                } catch (Exception e) {
                    log.warn("Failed to process IAM user {}: {}", user.userName(), e.getMessage());
                }
            });

            iam.listRolesPaginator().roles().forEach(role -> {
                try {
                    if (!role.path().startsWith("/aws-service-role/") && !role.path().startsWith("/service-role/")) {
                        results.add(buildRoleDto(role));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process IAM role {}: {}", role.roleName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("IAM scan failed: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single IAM user.
     *
     * <p>Checks MFA status, access key age and last usage, and password last used.
     * Computes effective days since last activity as the minimum of login and key usage.</p>
     */
    private ResourceDto buildUserDto(IamClient iam, User user) {
        boolean mfaEnabled = !iam.listMFADevices((ListMfaDevicesRequest) ListMfaDevicesRequest.builder().userName(user.userName()).build()).mfaDevices().isEmpty();
        int daysSinceLogin = user.passwordLastUsed() != null ? (int) Duration.between(user.passwordLastUsed(), Instant.now()).toDays() : 365;
        List<AccessKeyMetadata> keys = iam.listAccessKeys((ListAccessKeysRequest) ListAccessKeysRequest.builder().userName(user.userName()).build()).accessKeyMetadata();
        int activeKeys = (int) keys.stream().filter(k -> "Active".equals(k.statusAsString())).count();
        int daysSinceKeyUsed = 365;

        for (AccessKeyMetadata key : keys) {
            if ("Active".equals(key.statusAsString())) {
                GetAccessKeyLastUsedResponse lastUsed = iam.getAccessKeyLastUsed((GetAccessKeyLastUsedRequest) GetAccessKeyLastUsedRequest.builder().accessKeyId(key.accessKeyId()).build());
                if (lastUsed.accessKeyLastUsed().lastUsedDate() != null) {
                    int days = (int) Duration.between(lastUsed.accessKeyLastUsed().lastUsedDate(), Instant.now()).toDays();
                    daysSinceKeyUsed = Math.min(daysSinceKeyUsed, days);
                }
            }
        }

        int effectiveDays = Math.min(daysSinceLogin, daysSinceKeyUsed);
        String recommendation = engine.getIamUserRecommendation(mfaEnabled, effectiveDays);
        String description = String.format("MFA: %s / Keys: %d / Last active: %dd ago", mfaEnabled ? "enabled" : "disabled", activeKeys, effectiveDays);
        var dto = new ResourceDto();
        dto.setRegion("global");
        dto.setResourceType("IAM User");
        dto.setResourceId(user.userId());
        dto.setResourceName(user.userName());
        dto.setInstanceType(description);
        dto.setState(mfaEnabled ? "secured" : "review");
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (user.createDate() != null) {
            dto.setCreatedDate(user.createDate().toString());
        }

        return dto;
    }

    /**
     * Builds a ResourceDto for a single IAM role.
     *
     * <p>Checks days since role was last used. Roles unused for over 90 days are
     * flagged as "unused".</p>
     */
    private ResourceDto buildRoleDto(Role role) {
        int daysSinceUsed = 365;
        if (role.roleLastUsed() != null && role.roleLastUsed().lastUsedDate() != null) {
            daysSinceUsed = (int) Duration.between(role.roleLastUsed().lastUsedDate(), Instant.now()).toDays();
        }

        String recommendation = engine.getIamRoleRecommendation(daysSinceUsed);
        var dto = new ResourceDto();
        dto.setRegion("global");
        dto.setResourceType("IAM Role");
        dto.setResourceId(role.roleId());
        dto.setResourceName(role.roleName());
        dto.setInstanceType("Last used: " + daysSinceUsed + "d ago");
        dto.setState(daysSinceUsed > 90 ? "unused" : "active");
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (role.createDate() != null) {
            dto.setCreatedDate(role.createDate().toString());
        }

        return dto;
    }
}
