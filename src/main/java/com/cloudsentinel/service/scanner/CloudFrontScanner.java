package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.DistributionSummary;
import software.amazon.awssdk.services.cloudfront.model.ListDistributionsResponse;

/**
 * Scans AWS CloudFront distributions for disabled or misconfigured distributions.
 *
 * <p>Checks whether distributions are enabled and their deployment status.
 * Delegates to {@link RecommendationEngine#getCloudFrontRecommendation} for classification.
 * Runs as a global scanner (us-east-1).</p>
 */
@Component
public class CloudFrontScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(CloudFrontScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public CloudFrontScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public boolean isGlobal() {
        return true;
    }

    /**
     * Scans CloudFront distributions globally (us-east-1).
     *
     * <p>Calls {@code listDistributions} and iterates over all distribution summaries.
     * Errors on individual distributions are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan (ignored; uses us-east-1)
     * @return list of discovered distributions with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var cloudFront = ReadOnlyAwsClientFactory.build(CloudFrontClient.builder(), creds, Region.of(globalRegion()))) {
            ListDistributionsResponse response = cloudFront.listDistributions();
            if (response.distributionList() != null && response.distributionList().items() != null) {
                for (DistributionSummary dist : response.distributionList().items()) {
                    try {
                        results.add(this.buildDto(dist));
                    } catch (Exception e) {
                        log.warn("Failed to process CloudFront distribution {}: {}", dist.id(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("CloudFront scan failed: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single CloudFront distribution.
     *
     * <p>Checks enabled/disabled state and origin count. Cost is $0 (usage-based pricing).</p>
     */
    private ResourceDto buildDto(DistributionSummary dist) {
        String distributionId = dist.id();
        String domainName = dist.domainName();
        boolean enabled = dist.enabled() != null && dist.enabled();
        String status = dist.status();
        int originCount = dist.origins() != null && dist.origins().items() != null ? dist.origins().items().size() : 0;
        String state = enabled ? "enabled" : "disabled";
        String recommendation = engine.getCloudFrontRecommendation(enabled);
        ResourceDto dto = new ResourceDto();
        dto.setRegion("global");
        dto.setResourceType("CloudFront");
        dto.setResourceId(distributionId);
        dto.setResourceName(domainName);
        dto.setInstanceType(domainName + " / " + status + " / Origins: " + originCount);
        dto.setState(state);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (dist.lastModifiedTime() != null) {
            dto.setCreatedDate(dist.lastModifiedTime().toString());
        }

        return dto;
    }
}
