package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusRequest;
import software.amazon.awssdk.services.cloudtrail.model.GetTrailStatusResponse;
import software.amazon.awssdk.services.cloudtrail.model.Trail;

/**
 * Scans AWS CloudTrail trails for disabled logging or missing multi-region coverage.
 *
 * <p>Checks whether each trail is actively logging and if it is multi-region.
 * Delegates to {@link RecommendationEngine#getCloudTrailRecommendation} for classification.
 * Runs as a global scanner.</p>
 */
@Component
public class CloudTrailScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(CloudTrailScanner.class);
    private final RecommendationEngine engine;

    public CloudTrailScanner(RecommendationEngine engine) {
        this.engine = engine;
    }

    public boolean isGlobal() {
        return true;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans CloudTrail trails globally.
     *
     * <p>Calls {@code describeTrails} and {@code getTrailStatus} per trail.
     * Builds DTOs inline since trail processing is straightforward.
     * Errors on individual trails are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered trails with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ct = ReadOnlyAwsClientFactory.build(CloudTrailClient.builder(), creds, Region.of(region))) {
            for (Trail trail : ct.describeTrails().trailList()) {
                try {
                    GetTrailStatusResponse status = ct.getTrailStatus((GetTrailStatusRequest) GetTrailStatusRequest.builder().name(trail.trailARN()).build());
                    boolean isLogging = Boolean.TRUE.equals(status.isLogging());
                    boolean isMultiRegion = Boolean.TRUE.equals(trail.isMultiRegionTrail());
                    String recommendation = this.engine.getCloudTrailRecommendation(isLogging);
                    String description = String.format("Logging: %s / Multi-region: %s / S3: %s", isLogging ? "on" : "off", isMultiRegion ? "yes" : "no", trail.s3BucketName() != null ? trail.s3BucketName() : "none");
                    ResourceDto dto = new ResourceDto();
                    dto.setRegion(trail.homeRegion() != null ? trail.homeRegion() : region);
                    dto.setResourceType("CloudTrail");
                    dto.setResourceId(trail.trailARN());
                    dto.setResourceName(trail.name());
                    dto.setInstanceType(description);
                    dto.setState(isLogging ? "logging" : "disabled");
                    dto.setMonthlyCostUsd(0.0);
                    dto.setRecommendation(recommendation);
                    results.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to process CloudTrail {}: {}", trail.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("CloudTrail scan failed: {}", e.getMessage());
        }

        return results;
    }
}
