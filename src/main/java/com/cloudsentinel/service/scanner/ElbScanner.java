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
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer;

/**
 * Scans Elastic Load Balancers (ALB/NLB) for cost tracking and review.
 *
 * <p>Checks load balancer type and state. Reports per-type hourly cost.
 * Delegates to {@link RecommendationEngine#getElbRecommendation} for classification.</p>
 */
@Component
public class ElbScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ElbScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ElbScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Elastic Load Balancers (ALB/NLB) in the given region.
     *
     * <p>Calls {@code describeLoadBalancersPaginator} and iterates over all load balancers.
     * Errors on individual load balancers are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered load balancers with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var elb = ReadOnlyAwsClientFactory.build(ElasticLoadBalancingV2Client.builder(), creds, Region.of(region))) {

            elb.describeLoadBalancersPaginator().loadBalancers().stream().forEach(lb -> {
                try {
                    results.add(buildDto(lb, region));
                } catch (Exception e) {
                    log.warn("Failed to process ELB {}: {}", lb.loadBalancerName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("ELB scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single load balancer.
     *
     * <p>Populates load balancer type (application/network) and state. Cost is based on
     * per-type hourly rate from PricingService.</p>
     */
    private ResourceDto buildDto(LoadBalancer lb, String region) {
        String type = lb.typeAsString();
        String arn = lb.loadBalancerArn();
        String resourceId = arn.substring(arn.lastIndexOf('/') + 1);
        String state = lb.state() != null ? lb.state().code().toString() : "unknown";
        double cost = pricingService.getElbPrice(type, region);
        String recommendation = engine.getElbRecommendation();
        var dto = new ResourceDto();
        dto.setRegion(region);
        String capitalizedType = type.substring(0, 1).toUpperCase() + type.substring(1).toLowerCase();
        dto.setResourceType(capitalizedType + " Load Balancer");
        dto.setResourceId(resourceId);
        dto.setResourceName(lb.loadBalancerName());
        dto.setInstanceType(type);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (lb.createdTime() != null) {
            dto.setCreatedDate(lb.createdTime().toString());
        }
        return dto;
    }
}
