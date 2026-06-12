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
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.Api;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

/**
 * Scans AWS API Gateway V2 APIs for idle or low-traffic endpoints.
 *
 * <p>Checks 7-day CloudWatch request count to calculate average daily requests.
 * Delegates to {@link RecommendationEngine#getApiGatewayRecommendation} for classification.</p>
 */
@Component
public class ApiGatewayScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ApiGatewayScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ApiGatewayScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans API Gateway V2 APIs in the given region.
     *
     * <p>Calls {@code getApis} and queries CloudWatch {@code Count} metric per API.
     * Errors on individual APIs are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered APIs with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var apiGateway = ReadOnlyAwsClientFactory.build(ApiGatewayV2Client.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            for (Api api : apiGateway.getApis().items()) {
                try {
                    results.add(this.buildDto(api, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process API Gateway {}: {}", api.apiId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("API Gateway scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single API Gateway V2 API.
     *
     * <p>Computes 7-day average daily request count from CloudWatch and estimates monthly cost.</p>
     */
    private ResourceDto buildDto(Api api, CloudWatchClient cw, String region) {
        String apiId = api.apiId();
        String description = api.protocolType() + " / " + api.apiEndpoint();
        double totalRequests = this.getRequestCount(cw, apiId);
        double requestsPerDay = totalRequests / 7.0;
        String recommendation = engine.getApiGatewayRecommendation(requestsPerDay);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("API Gateway");
        dto.setResourceId(apiId);
        dto.setResourceName(api.name());
        dto.setInstanceType(description);
        dto.setState("active");
        dto.setMonthlyCostUsd(this.pricingService.getApiGatewayPrice(requestsPerDay, region));
        dto.setRecommendation(recommendation);
        if (api.createdDate() != null) {
            dto.setCreatedDate(api.createdDate().toString());
        }

        return dto;
    }

    private double getRequestCount(CloudWatchClient cw, String apiId) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/ApiGateway").metricName("Count").dimensions(Dimension.builder().name("ApiId").value(apiId).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).sum();
        } catch (Exception e) {
            log.debug("CloudWatch Count query failed for API Gateway {}: {}", apiId, e.getMessage());
            return 0.0;
        }
    }
}
