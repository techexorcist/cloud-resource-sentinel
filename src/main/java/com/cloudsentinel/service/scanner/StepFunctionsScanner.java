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
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.StateMachineListItem;

/**
 * Scans Step Functions state machines for idle or low-execution workflows.
 *
 * <p>Checks 7-day CloudWatch ExecutionsStarted count. Reports type (Standard/Express)
 * and status. Delegates to {@link RecommendationEngine#getStepFunctionsRecommendation}
 * for classification.</p>
 */
@Component
public class StepFunctionsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(StepFunctionsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public StepFunctionsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Step Functions state machines in the given region.
     *
     * <p>Calls {@code listStateMachinesPaginator}, {@code describeStateMachine} per machine,
     * and queries CloudWatch ExecutionsStarted. Errors on individual state machines are
     * logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered state machines with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var sfn = ReadOnlyAwsClientFactory.build(SfnClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            sfn.listStateMachinesPaginator().stateMachines().forEach((stateMachine) -> {
                try {
                    results.add(this.buildDto(stateMachine, sfn, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process Step Function {}: {}", stateMachine.name(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Step Functions scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Step Functions state machine.
     *
     * <p>Fetches state machine type (Standard/Express) and 7-day execution count from
     * CloudWatch. Cost is based on average daily executions.</p>
     */
    private ResourceDto buildDto(StateMachineListItem sm, SfnClient sfn, CloudWatchClient cw, String region) {
        String stateMachineArn = sm.stateMachineArn();
        String stateMachineName = sm.name();
        DescribeStateMachineResponse detail = sfn.describeStateMachine((r) -> r.stateMachineArn(stateMachineArn));
        String type = detail.typeAsString();
        String status = detail.statusAsString();
        String description = type + " / " + stateMachineName;
        double totalExecutions = this.getExecutionsStarted(cw, stateMachineArn);
        double executionsPerDay = totalExecutions / 7.0;
        String recommendation = engine.getStepFunctionsRecommendation(executionsPerDay);

        double cost = this.pricingService.getStepFunctionsPrice(executionsPerDay, region);
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Step Functions");
        dto.setResourceId(stateMachineArn);
        dto.setResourceName(stateMachineName);
        dto.setInstanceType(description);
        dto.setState(status);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (sm.creationDate() != null) {
            dto.setCreatedDate(sm.creationDate().toString());
        }

        return dto;
    }

    private double getExecutionsStarted(CloudWatchClient cw, String stateMachineArn) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/States").metricName("ExecutionsStarted").dimensions(Dimension.builder().name("StateMachineArn").value(stateMachineArn).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).sum();
        } catch (Exception e) {
            log.debug("CloudWatch ExecutionsStarted query failed for Step Function {}: {}", stateMachineArn, e.getMessage());
            return 0.0;
        }
    }
}
