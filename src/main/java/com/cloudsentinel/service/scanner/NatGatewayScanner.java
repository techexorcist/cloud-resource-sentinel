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
import software.amazon.awssdk.services.ec2.model.DescribeNatGatewaysRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Scans NAT Gateways for low-traffic or idle gateways.
 *
 * <p>Checks 7-day CloudWatch BytesOutToDestination metric. Filters to available
 * gateways only. Delegates to {@link RecommendationEngine#getNatGatewayRecommendation}
 * for classification.</p>
 */
@Component
public class NatGatewayScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(NatGatewayScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public NatGatewayScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans NAT Gateways in the given region.
     *
     * <p>Calls {@code describeNatGateways} filtered to "available" state, then queries
     * CloudWatch BytesOutToDestination per gateway. Errors on individual gateways are
     * logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered NAT Gateways with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            DescribeNatGatewaysRequest request = (DescribeNatGatewaysRequest) DescribeNatGatewaysRequest.builder().filter(new Filter[]{(Filter) Filter.builder().name("state").values(new String[]{"available"}).build()}).build();

            for (NatGateway natGw : ec2.describeNatGateways(request).natGateways()) {
                try {
                    results.add(buildDto(natGw, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process NAT Gateway {}: {}", natGw.natGatewayId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("NAT Gateway scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single NAT Gateway.
     *
     * <p>Fetches 7-day bytes-out traffic from CloudWatch. Cost is the fixed NAT Gateway
     * hourly rate (data transfer charges are separate).</p>
     */
    private ResourceDto buildDto(NatGateway natGw, CloudWatchClient cw, String region) {
        String natGwId = natGw.natGatewayId();
        double bytesOut = getBytesOutToDestination(cw, natGwId);
        double cost = pricingService.getNatGatewayPrice(region);
        String recommendation = engine.getNatGatewayRecommendation(bytesOut);
        String name = natGw.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(natGwId);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("NAT Gateway");
        dto.setResourceId(natGwId);
        dto.setResourceName(name);
        dto.setInstanceType("NAT Gateway");
        dto.setState(natGw.stateAsString());
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (natGw.createTime() != null) {
            dto.setCreatedDate(natGw.createTime().toString());
        }

        return dto;
    }

    private double getBytesOutToDestination(CloudWatchClient cw, String natGwId) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/NATGateway").metricName("BytesOutToDestination").dimensions(Dimension.builder().name("NatGatewayId").value(natGwId).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).sum();
        } catch (Exception e) {
            log.debug("CloudWatch BytesOutToDestination query failed for {}: {}", natGwId, e.getMessage());
            return 0.0;
        }
    }
}
