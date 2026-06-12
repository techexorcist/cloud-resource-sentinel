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
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;

/**
 * Scans Lambda functions for idle or error-prone functions.
 *
 * <p>Checks 7-day CloudWatch invocation count, error rate, and average duration.
 * Determines last invocation date from CloudWatch data points. Delegates to
 * {@link RecommendationEngine#getLambdaRecommendation} for classification.</p>
 */
@Component
public class LambdaScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(LambdaScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public LambdaScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Lambda functions in the given region.
     *
     * <p>Calls {@code listFunctionsPaginator} and queries CloudWatch Invocations, Errors,
     * and Duration metrics per function. Errors on individual functions are logged
     * and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Lambda functions with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var lambda = ReadOnlyAwsClientFactory.build(LambdaClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            lambda.listFunctionsPaginator().functions().forEach(fn -> {
                try {
                    results.add(buildDto(fn, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process Lambda {}: {}", fn.functionName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Lambda scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Lambda function.
     *
     * <p>Computes 7-day average daily invocations, error rate, and average duration from
     * CloudWatch. Determines last invocation date. Cost is based on monthly invocations,
     * duration, and memory size.</p>
     */
    private ResourceDto buildDto(FunctionConfiguration fn, CloudWatchClient cw, String region) {
        String functionName = fn.functionName();
        int memoryMb = fn.memorySize();
        double invocationsPerDay = getMetricAverage(cw, functionName, "Invocations", Statistic.SUM);
        double errorsPerDay = getMetricAverage(cw, functionName, "Errors", Statistic.SUM);
        double avgDurationMs = getMetricAverage(cw, functionName, "Duration", Statistic.AVERAGE);
        String lastInvoked = getLastInvokedDate(cw, functionName);
        String recommendation = engine.getLambdaRecommendation(invocationsPerDay, errorsPerDay);
        long monthlyInvocations = (long) (invocationsPerDay * 30.0);
        double cost = pricingService.getLambdaPrice(monthlyInvocations, (long) avgDurationMs, memoryMb, region);
        String runtimeStr = fn.runtime() != null ? fn.runtimeAsString() : "unknown";
        String description = String.format("%s / %dMB / %.0f inv/day / Last invoked: %s", runtimeStr, memoryMb, invocationsPerDay, lastInvoked);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Lambda");
        dto.setResourceId(fn.functionArn());
        dto.setResourceName(functionName);
        dto.setInstanceType(description);
        dto.setState(invocationsPerDay > 0.0 ? "active" : "idle");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (fn.lastModified() != null) {
            dto.setCreatedDate(fn.lastModified());
        }

        return dto;
    }

    private String getLastInvokedDate(CloudWatchClient cw, String functionName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(30L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/Lambda").metricName("Invocations").dimensions(Dimension.builder().name("FunctionName").value(functionName).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().filter(dp -> dp.sum() > 0.0).map(Datapoint::timestamp).max(Instant::compareTo).map(ts -> {
                long daysAgo = ChronoUnit.DAYS.between(ts, Instant.now());
                return daysAgo == 0L ? "today" : daysAgo + "d ago";
            }).orElse(">30d ago");
        } catch (Exception e) {
            log.debug("CloudWatch last invocation query failed for Lambda {}: {}", functionName, e.getMessage());
            return "unknown";
        }
    }

    private double getMetricAverage(CloudWatchClient cw, String functionName, String metricName, Statistic stat) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/Lambda").metricName(metricName).dimensions(Dimension.builder().name("FunctionName").value(functionName).build()).startTime(start).endTime(end).period(86400).statistics(new Statistic[]{stat}).build());
            return stat == Statistic.AVERAGE ? response.datapoints().stream().mapToDouble(Datapoint::average).average().orElse(0.0) : response.datapoints().stream().mapToDouble(Datapoint::sum).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch {} query failed for Lambda {}: {}", metricName, functionName, e.getMessage());
            return 0.0;
        }
    }
}
