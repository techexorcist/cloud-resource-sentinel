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
import software.amazon.awssdk.services.elasticbeanstalk.ElasticBeanstalkClient;
import software.amazon.awssdk.services.elasticbeanstalk.model.EnvironmentDescription;

/**
 * Scans Elastic Beanstalk environments for unhealthy or non-ready environments.
 *
 * <p>Checks environment status and health. Skips terminated environments.
 * Delegates to {@link RecommendationEngine#getElasticBeanstalkRecommendation}
 * for classification.</p>
 */
@Component
public class ElasticBeanstalkScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ElasticBeanstalkScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ElasticBeanstalkScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Elastic Beanstalk environments in the given region.
     *
     * <p>Calls {@code describeEnvironments} and skips terminated environments.
     * Errors on individual environments are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered environments with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var beanstalk = ReadOnlyAwsClientFactory.build(ElasticBeanstalkClient.builder(), creds, Region.of(region))) {

            for (EnvironmentDescription env : beanstalk.describeEnvironments().environments()) {
                try {
                    String status = env.statusAsString();
                    if (!"Terminated".equals(status)) {
                        results.add(buildDto(env, region));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process Elastic Beanstalk environment {}: {}", env.environmentName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Elastic Beanstalk scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Elastic Beanstalk environment.
     *
     * <p>Populates solution stack, health status, and environment status. Cost is $0
     * (Beanstalk itself is free; underlying resources are charged separately).</p>
     */
    private ResourceDto buildDto(EnvironmentDescription env, String region) {
        String name = env.environmentName();
        String status = env.statusAsString();
        String health = env.healthAsString();
        String solutionStack = env.solutionStackName();
        String description = solutionStack + " / " + health + " / " + status;
        String recommendation = engine.getElasticBeanstalkRecommendation(status, health);

        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Elastic Beanstalk");
        dto.setResourceId(env.environmentId());
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(status);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (env.dateCreated() != null) {
            dto.setCreatedDate(env.dateCreated().toString());
        }
        return dto;
    }
}
