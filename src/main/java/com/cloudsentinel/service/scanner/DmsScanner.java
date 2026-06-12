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
import software.amazon.awssdk.services.databasemigration.DatabaseMigrationClient;
import software.amazon.awssdk.services.databasemigration.model.DescribeReplicationTasksRequest;
import software.amazon.awssdk.services.databasemigration.model.Filter;
import software.amazon.awssdk.services.databasemigration.model.ReplicationInstance;

/**
 * Scans AWS DMS replication instances for idle or task-less instances.
 *
 * <p>Checks instance status and whether any replication tasks are attached.
 * Converts DMS instance class to RDS format for pricing lookup. Delegates to
 * {@link RecommendationEngine#getDmsRecommendation} for classification.</p>
 */
@Component
public class DmsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(DmsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public DmsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans DMS replication instances in the given region.
     *
     * <p>Calls {@code describeReplicationInstances} and checks for attached replication tasks.
     * Errors on individual instances are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered replication instances with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var dms = ReadOnlyAwsClientFactory.build(DatabaseMigrationClient.builder(), creds, Region.of(region))) {
            for (ReplicationInstance instance : dms.describeReplicationInstances().replicationInstances()) {
                try {
                    results.add(buildDto(dms, instance, region));
                } catch (Exception e) {
                    log.warn("Failed to process DMS replication instance {}: {}", instance.replicationInstanceIdentifier(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("DMS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single DMS replication instance.
     *
     * <p>Checks for attached replication tasks and converts DMS instance class
     * (e.g. "dms.t3.medium") to RDS format ("db.t3.medium") for pricing lookup.</p>
     */
    private ResourceDto buildDto(DatabaseMigrationClient dms, ReplicationInstance instance, String region) {
        String status = instance.replicationInstanceStatus();
        String instanceClass = instance.replicationInstanceClass() != null ? instance.replicationInstanceClass() : "Unknown";
        String engineVersion = instance.engineVersion() != null ? instance.engineVersion() : "Unknown";

        boolean hasTasks = false;
        if (status != null && status.contains("available")) {
            try {
                hasTasks = !dms.describeReplicationTasks(DescribeReplicationTasksRequest.builder()
                        .filters(Filter.builder()
                                .name("replication-instance-arn")
                                .values(instance.replicationInstanceArn())
                                .build())
                        .build()).replicationTasks().isEmpty();
            } catch (Exception e) {
                log.debug("Cannot check replication tasks for {}: {}", instance.replicationInstanceIdentifier(), e.getMessage());
            }
        }
        String recommendation = this.engine.getDmsRecommendation(status, hasTasks);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("DMS");
        dto.setResourceId(instance.replicationInstanceIdentifier());
        dto.setResourceName(instance.replicationInstanceIdentifier());
        dto.setInstanceType(instanceClass + " / " + engineVersion);
        dto.setState(status != null ? status : "unknown");
        // DMS instance pricing is similar to RDS; convert "dms.t3.medium" -> "db.t3.medium" for lookup
        boolean running = status != null && status.contains("available");
        double cost = 0.0;
        if (running && instanceClass != null && !instanceClass.equals("Unknown")) {
            String rdsClass = instanceClass.replace("dms.", "db.");
            cost = pricingService.getRdsPrice(rdsClass, "mysql", region);
        }
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (instance.instanceCreateTime() != null) {
            dto.setCreatedDate(instance.instanceCreateTime().toString());
        }

        return dto;
    }
}
