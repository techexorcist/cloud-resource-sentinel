package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.DescribeSecretResponse;
import software.amazon.awssdk.services.secretsmanager.model.ListSecretsResponse;
import software.amazon.awssdk.services.secretsmanager.model.SecretListEntry;

/**
 * Scans Secrets Manager secrets for stale, unrotated, or misclassified secrets.
 *
 * <p>Checks days since last access, rotation enablement, and whether non-sensitive
 * values (config, URLs) are stored as secrets. Delegates to
 * {@link RecommendationEngine#getSecretRecommendation} for classification.</p>
 */
@Component
public class SecretsManagerScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(SecretsManagerScanner.class);
    private static final Pattern NON_SENSITIVE_PATTERN = Pattern.compile("(?i)(config|endpoint|url|host|port|region|env|version|feature[_-]?flag|toggle|setting)");
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public SecretsManagerScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans Secrets Manager secrets in the given region.
     *
     * <p>Calls {@code listSecretsPaginator} and {@code describeSecret} per secret.
     * Errors on individual secrets are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered secrets with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var sm = ReadOnlyAwsClientFactory.build(SecretsManagerClient.builder(), creds, Region.of(region))) {
            for (ListSecretsResponse page : sm.listSecretsPaginator()) {
                for (SecretListEntry secret : page.secretList()) {
                    try {
                        results.add(this.buildDto(sm, secret, region));
                    } catch (Exception e) {
                        log.warn("Failed to process secret {}: {}", secret.name(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Secrets Manager scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Secrets Manager secret.
     *
     * <p>Checks days since last access, rotation enablement, and whether non-sensitive
     * values (config, URLs) are stored as secrets. Cost is the fixed per-secret monthly rate.</p>
     */
    private ResourceDto buildDto(SecretsManagerClient sm, SecretListEntry secret, String region) {
        DescribeSecretResponse detail = sm.describeSecret((DescribeSecretRequest) DescribeSecretRequest.builder().secretId(secret.arn()).build());
        boolean rotationEnabled = Boolean.TRUE.equals(detail.rotationEnabled());
        Instant lastAccessed = detail.lastAccessedDate();
        int daysSinceAccessed = lastAccessed != null ? (int) Duration.between(lastAccessed, Instant.now()).toDays() : 365;
        boolean looksNonSensitive = NON_SENSITIVE_PATTERN.matcher(secret.name()).find();
        String recommendation = this.engine.getSecretRecommendation(daysSinceAccessed, rotationEnabled, looksNonSensitive);
        double cost = this.pricingService.getSecretPrice(region);
        String rotationInfo = rotationEnabled ? "Rotation: enabled" : "Rotation: disabled";
        String accessInfo = lastAccessed != null ? daysSinceAccessed + "d ago" : "never";
        String description = String.format("%s / Last accessed: %s", rotationInfo, accessInfo);
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Secrets Manager");
        dto.setResourceId(secret.arn());
        dto.setResourceName(secret.name());
        dto.setInstanceType(description);
        dto.setState(daysSinceAccessed > 90 ? "unused" : "active");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (detail.createdDate() != null) {
            dto.setCreatedDate(detail.createdDate().toString());
        }

        return dto;
    }
}
