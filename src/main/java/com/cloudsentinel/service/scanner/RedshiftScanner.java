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
import software.amazon.awssdk.services.redshift.RedshiftClient;

/**
 * Scans Redshift clusters for underutilized or idle data warehouse clusters.
 *
 * <p>Checks 7-day average CPU utilization and cluster status. Prices based on node
 * type and node count. Delegates to {@link RecommendationEngine#getRedshiftRecommendation}
 * for classification.</p>
 */
@Component
public class RedshiftScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(RedshiftScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public RedshiftScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Redshift clusters in the given region.
     *
     * <p>Calls {@code describeClustersPaginator} and queries CloudWatch CPUUtilization
     * per cluster. Builds DTOs inline since each cluster has its own pricing calculation.
     * Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Redshift clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var redshift = ReadOnlyAwsClientFactory.build(RedshiftClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            redshift.describeClustersPaginator().clusters().forEach((cluster) -> {
                try {
                    String clusterId = cluster.clusterIdentifier();
                    String nodeType = cluster.nodeType();
                    int nodeCount = cluster.numberOfNodes();
                    String status = cluster.clusterStatus();
                    double cpu = this.getCpuUtilization(cw, clusterId);
                    String recommendation = this.engine.getRedshiftRecommendation(cpu, status);
                    double cost = this.pricingService.getRedshiftPrice(nodeType, nodeCount, region);
                    String description = String.format("%s x%d nodes", nodeType, nodeCount);
                    ResourceDto dto = new ResourceDto();
                    dto.setRegion(region);
                    dto.setResourceType("Redshift");
                    dto.setResourceId(clusterId);
                    dto.setResourceName(clusterId);
                    dto.setInstanceType(description);
                    dto.setState(status);
                    dto.setCpuUtilizationAvg(cpu);
                    dto.setMonthlyCostUsd(cost);
                    dto.setRecommendation(recommendation);
                    if (cluster.clusterCreateTime() != null) {
                        dto.setCreatedDate(cluster.clusterCreateTime().toString());
                    }

                    results.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to process Redshift cluster {}: {}", cluster.clusterIdentifier(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Redshift scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    private double getCpuUtilization(CloudWatchClient cw, String clusterId) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/Redshift").metricName("CPUUtilization").dimensions(Dimension.builder().name("ClusterIdentifier").value(clusterId).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.AVERAGE).build());
            return response.datapoints().stream().mapToDouble(Datapoint::average).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch CPU query failed for Redshift {}: {}", clusterId, e.getMessage());
            return 0.0;
        }
    }
}
