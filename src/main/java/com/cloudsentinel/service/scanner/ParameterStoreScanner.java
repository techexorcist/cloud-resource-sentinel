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
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.DescribeParametersResponse;
import software.amazon.awssdk.services.ssm.model.ParameterMetadata;
import software.amazon.awssdk.services.ssm.model.ParameterTier;

/**
 * Scans SSM Parameter Store parameters for stale or misconfigured entries.
 *
 * <p>Checks days since last modification, parameter tier, and whether sensitive-looking
 * parameter names use SecureString type. Delegates to
 * {@link RecommendationEngine#getParameterRecommendation} for classification.</p>
 */
@Component
public class ParameterStoreScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ParameterStoreScanner.class);
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key|private[_-]?key|credential|auth[_-]?token|access[_-]?key|client[_-]?secret|db[_-]?pass|connection[_-]?string)");
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ParameterStoreScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.GOVERNANCE;
    }

    /**
     * Scans SSM Parameter Store parameters in the given region.
     *
     * <p>Calls {@code describeParametersPaginator} and iterates over all parameters.
     * Errors on individual parameters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered parameters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ssm = ReadOnlyAwsClientFactory.build(SsmClient.builder(), creds, Region.of(region))) {

            for (DescribeParametersResponse page : ssm.describeParametersPaginator()) {
                for (ParameterMetadata param : page.parameters()) {
                    try {
                        results.add(buildDto(param, region));
                    } catch (Exception e) {
                        log.warn("Failed to process parameter {}: {}", param.name(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Parameter Store scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single SSM parameter.
     *
     * <p>Checks days since last modification, parameter tier (Standard vs Advanced),
     * and whether sensitive-looking parameter names use SecureString type. Only Advanced
     * tier parameters incur cost.</p>
     */
    private ResourceDto buildDto(ParameterMetadata param, String region) {
        String name = param.name();
        String type = param.typeAsString();
        ParameterTier tier = param.tier();
        boolean isAdvanced = tier == ParameterTier.ADVANCED;
        Instant lastModified = param.lastModifiedDate();
        int daysSinceModified = lastModified != null ? (int) Duration.between(lastModified, Instant.now()).toDays() : 365;
        boolean hasSensitiveName = SENSITIVE_PATTERN.matcher(name).find();
        boolean isSecureString = "SecureString".equals(type);
        boolean shouldBeSecret = hasSensitiveName && !isSecureString;
        String recommendation = engine.getParameterRecommendation(daysSinceModified, type, shouldBeSecret);
        double cost = isAdvanced ? pricingService.getAdvancedParameterPrice(region) : 0.0;
        String description = String.format("%s / %s / Modified: %dd ago", type, isAdvanced ? "Advanced" : "Standard", daysSinceModified);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("SSM Parameter");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(shouldBeSecret ? "review" : "active");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (lastModified != null) {
            dto.setCreatedDate(lastModified.toString());
        }

        return dto;
    }
}
