package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBClusterMember;
import software.amazon.awssdk.services.rds.model.DBInstance;

/**
 * Scans Aurora DB clusters for underutilized or idle databases.
 *
 * <p>Checks 7-day average CPU utilization and cluster status. Filters to aurora-engine
 * clusters only. Prices per-member with instance class lookup (NPE-safe fallback).
 * Delegates to {@link RecommendationEngine#getAuroraRecommendation} for classification.</p>
 */
@Component
public class AuroraScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(AuroraScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public AuroraScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Aurora DB clusters in the given region.
     *
     * <p>Calls {@code describeDBClustersPaginator}, filters to aurora-engine clusters,
     * and queries CloudWatch CPU per cluster. Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Aurora clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var rds = ReadOnlyAwsClientFactory.build(RdsClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            rds.describeDBClustersPaginator().dbClusters().forEach((cluster) -> {
                try {
                    String engineName = cluster.engine();
                    if (engineName != null && engineName.startsWith("aurora")) {
                        results.add(this.buildDto(rds, cluster, cw, region));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process Aurora cluster {}: {}", cluster.dbClusterIdentifier(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Aurora scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Aurora cluster.
     *
     * <p>Looks up the first member's instance class via {@code describeDBInstances} for pricing.
     * Falls back to db.r5.large estimate if member lookup fails (NPE-safe). Multiplies per-member
     * cost by member count.</p>
     */
    private ResourceDto buildDto(RdsClient rds, DBCluster cluster, CloudWatchClient cw, String region) {
        String clusterId = cluster.dbClusterIdentifier();
        String status = cluster.status();
        String engineName = cluster.engine();
        String engineVersion = cluster.engineVersion();
        int memberCount = cluster.dbClusterMembers() != null ? cluster.dbClusterMembers().size() : 0;
        double cpu = this.getCpuUtilization(cw, clusterId);
        String recommendation = this.engine.getAuroraRecommendation(cpu, status);
        double cost = 0.0;
        String memberClass = null;
        if (memberCount > 0 && cluster.dbClusterMembers() != null && !cluster.dbClusterMembers().isEmpty()) {
            try {
                DBClusterMember firstMember = cluster.dbClusterMembers().getFirst();
                String firstMemberId = firstMember != null ? firstMember.dbInstanceIdentifier() : null;
                if (firstMemberId == null || firstMemberId.isEmpty()) throw new IllegalStateException("No member ID");
                List<DBInstance> instances = rds.describeDBInstances((b) -> b.dbInstanceIdentifier(firstMemberId)).dbInstances();
                if (instances.isEmpty()) throw new IllegalStateException("Member instance not found");
                DBInstance dbInstance = instances.getFirst();
                memberClass = dbInstance.dbInstanceClass();
                cost = this.pricingService.getAuroraPrice(memberClass, engineName, region) * memberCount;
            } catch (Exception e) {
                log.debug("Could not look up Aurora member instance class, using estimate: {}", e.getMessage());
                cost = this.pricingService.getAuroraPrice("db.r5.large", engineName, region) * memberCount;
            }
        }

        String description = String.format("%s %s / Members: %d%s", engineName, engineVersion, memberCount, memberClass != null ? " (" + memberClass + ")" : "");
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Aurora");
        dto.setResourceId(cluster.dbClusterArn());
        dto.setResourceName(clusterId);
        dto.setInstanceType(description);
        dto.setState(status);
        dto.setCpuUtilizationAvg(cpu);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (cluster.clusterCreateTime() != null) {
            dto.setCreatedDate(cluster.clusterCreateTime().toString());
        }

        return dto;
    }

    private double getCpuUtilization(CloudWatchClient cw, String clusterId) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/RDS").metricName("CPUUtilization").dimensions(Dimension.builder().name("DBClusterIdentifier").value(clusterId).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.AVERAGE).build());
            return response.datapoints().stream().mapToDouble(Datapoint::average).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch CPU query failed for Aurora {}: {}", clusterId, e.getMessage());
            return 0.0;
        }
    }
}
