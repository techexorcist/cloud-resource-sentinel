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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Scans EC2 instances for idle or stopped instances.
 *
 * <p>Checks 7-day average CPU utilization and instance state. Reports $0 cost for
 * stopped/terminated/shutting-down instances (EBS charged separately). Delegates to
 * {@link RecommendationEngine#getRecommendation} for classification.</p>
 */
@Component
public class Ec2Scanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(Ec2Scanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public Ec2Scanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans EC2 instances in the given region.
     *
     * <p>Calls {@code describeInstancesPaginator}, flattens reservations, and queries
     * CloudWatch CPUUtilization per instance. Errors on individual instances are logged
     * and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered EC2 instances with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            ec2.describeInstancesPaginator().reservations().stream()
                    .flatMap(reservation -> reservation.instances().stream())
                    .forEach(instance -> {
                        try {
                            results.add(buildDto(instance, cw, region));
                        } catch (Exception e) {
                            log.warn("Failed to process EC2 instance {}: {}", instance.instanceId(), e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.error("EC2 scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single EC2 instance.
     *
     * <p>Fetches 7-day average CPU utilization. Reports $0 cost for stopped, terminated,
     * or shutting-down instances (EBS volumes are charged separately).</p>
     */
    private ResourceDto buildDto(Instance instance, CloudWatchClient cw, String region) {
        String instanceId = instance.instanceId();
        String instanceType = instance.instanceType().toString();
        String state = instance.state().nameAsString();
        String name = instance.tags().stream()
                .filter(t -> "Name".equals(t.key()))
                .map(Tag::value)
                .findFirst()
                .orElse(instanceId);

        double cpu = getCpuUtilization(cw, "AWS/EC2", "InstanceId", instanceId);
        boolean notRunning = "stopped".equalsIgnoreCase(state) || "stopping".equalsIgnoreCase(state)
                || "shutting-down".equalsIgnoreCase(state) || "terminated".equalsIgnoreCase(state);
        double cost = notRunning ? 0.0 : pricingService.getEc2Price(instanceType, region);
        String recommendation = engine.getRecommendation(cpu, state);

        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("EC2");
        dto.setResourceId(instanceId);
        dto.setResourceName(name);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        dto.setCpuUtilizationAvg(cpu);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (instance.launchTime() != null) {
            dto.setCreatedDate(instance.launchTime().toString());
        }
        // Collect tags for grouping (environment, team, cost_center, project)
        if (instance.tags() != null && !instance.tags().isEmpty()) {
            var tagMap = new java.util.LinkedHashMap<String, String>();
            instance.tags().forEach(t -> tagMap.put(t.key(), t.value()));
            dto.setTags(tagMap);
        }
        return dto;
    }

    static double getCpuUtilization(CloudWatchClient cw, String namespace, String dimensionName, String dimensionValue) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder()
                    .namespace(namespace)
                    .metricName("CPUUtilization")
                    .dimensions(Dimension.builder().name(dimensionName).value(dimensionValue).build())
                    .startTime(start).endTime(end)
                    .period(86400)
                    .statistics(Statistic.AVERAGE)
                    .build());

            if (response.datapoints().isEmpty()) return 0.0;

            double avg = response.datapoints().stream()
                    .mapToDouble(Datapoint::average)
                    .average()
                    .orElse(0.0);
            return Math.round(avg * 100.0) / 100.0;
        } catch (Exception e) {
            log.debug("CloudWatch CPUUtilization query failed for {}/{}: {}", dimensionName, dimensionValue, e.getMessage());
            return 0.0;
        }
    }
}
