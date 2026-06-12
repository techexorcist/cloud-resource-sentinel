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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;

/**
 * Scans RDS instances for underutilized or idle databases.
 *
 * <p>Checks 7-day average CPU utilization and instance status. Filters out Aurora
 * cluster members (handled by AuroraScanner) to prevent double-counting. Delegates to
 * {@link RecommendationEngine#getRecommendation} for classification.</p>
 */
@Component
public class RdsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(RdsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public RdsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans RDS instances in the given region.
     *
     * <p>Calls {@code describeDBInstancesPaginator} and filters out Aurora cluster members
     * (handled by AuroraScanner) to prevent double-counting. Queries CloudWatch
     * CPUUtilization per instance. Errors on individual instances are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered RDS instances with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var rds = ReadOnlyAwsClientFactory.build(RdsClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            rds.describeDBInstancesPaginator().dbInstances().stream()
                    .filter(db -> db.dbClusterIdentifier() == null || db.dbClusterIdentifier().isEmpty())
                    .forEach(db -> {
                        try {
                            results.add(buildDto(db, cw, region));
                        } catch (Exception e) {
                            log.warn("Failed to process RDS instance {}: {}", db.dbInstanceIdentifier(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("RDS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single RDS instance.
     *
     * <p>Fetches 7-day average CPU utilization. Cost is based on instance class and engine type.</p>
     */
    private ResourceDto buildDto(DBInstance db, CloudWatchClient cw, String region) {
        String dbId = db.dbInstanceIdentifier();
        String instanceClass = db.dbInstanceClass();
        String state = db.dbInstanceStatus();
        String engineName = db.engine();
        double cpu = Ec2Scanner.getCpuUtilization(cw, "AWS/RDS", "DBInstanceIdentifier", dbId);
        double cost = pricingService.getRdsPrice(instanceClass, engineName, region);
        String recommendation = engine.getRecommendation(cpu, state);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("RDS");
        dto.setResourceId(dbId);
        dto.setResourceName(dbId);
        dto.setInstanceType(instanceClass);
        dto.setState(state);
        dto.setCpuUtilizationAvg(cpu);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (db.instanceCreateTime() != null) {
            dto.setCreatedDate(db.instanceCreateTime().toString());
        }

        return dto;
    }
}
