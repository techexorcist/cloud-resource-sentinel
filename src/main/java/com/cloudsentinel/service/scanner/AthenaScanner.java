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
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.GetWorkGroupRequest;
import software.amazon.awssdk.services.athena.model.WorkGroup;
import software.amazon.awssdk.services.athena.model.WorkGroupSummary;

/**
 * Scans AWS Athena workgroups for disabled or idle workgroups.
 *
 * <p>Checks workgroup state and engine version. Skips the default "primary" workgroup
 * to reduce noise. Delegates to {@link RecommendationEngine#getAthenaRecommendation}
 * for classification.</p>
 */
@Component
public class AthenaScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(AthenaScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public AthenaScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Athena workgroups in the given region.
     *
     * <p>Calls {@code listWorkGroups} and {@code getWorkGroup} per workgroup.
     * Skips the default "primary" workgroup. Errors on individual workgroups are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered workgroups with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var athena = ReadOnlyAwsClientFactory.build(AthenaClient.builder(), creds, Region.of(region))) {
            var workgroups = athena.listWorkGroups(r -> {}).workGroups();
            // Skip the default "primary" workgroup — it exists in every region and adds noise
            for (WorkGroupSummary summary : workgroups) {
                if ("primary".equals(summary.name())) continue;
                try {
                    results.add(buildDto(athena, summary, region));
                } catch (Exception e) {
                    log.warn("Failed to process Athena workgroup {}: {}", summary.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Athena scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Athena workgroup.
     *
     * <p>Fetches workgroup detail to determine engine version and output location.
     * Cost is $0 (Athena charges per query, not per workgroup).</p>
     */
    private ResourceDto buildDto(AthenaClient athena, WorkGroupSummary summary, String region) {
        WorkGroup workgroup = athena.getWorkGroup(GetWorkGroupRequest.builder()
                .workGroup(summary.name()).build()).workGroup();

        String state = summary.state() != null ? summary.state().toString() : "UNKNOWN";
        String name = summary.name();

        String engineVersion = "default";
        String outputLocation = "";
        if (workgroup.configuration() != null) {
            if (workgroup.configuration().engineVersion() != null
                    && workgroup.configuration().engineVersion().effectiveEngineVersion() != null) {
                engineVersion = workgroup.configuration().engineVersion().effectiveEngineVersion();
            }
            if (workgroup.configuration().resultConfiguration() != null
                    && workgroup.configuration().resultConfiguration().outputLocation() != null) {
                outputLocation = workgroup.configuration().resultConfiguration().outputLocation();
            }
        }

        String instanceType = engineVersion;
        if (!outputLocation.isEmpty()) {
            instanceType = engineVersion + " / " + outputLocation;
        }

        String recommendation = engine.getAthenaRecommendation(state, name);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Athena");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (summary.creationTime() != null) {
            dto.setCreatedDate(summary.creationTime().toString());
        }

        return dto;
    }
}
