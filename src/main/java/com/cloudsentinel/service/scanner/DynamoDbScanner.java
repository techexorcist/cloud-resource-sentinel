package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputDescription;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

/**
 * Scans DynamoDB tables for underutilized provisioned capacity or idle on-demand tables.
 *
 * <p>Checks 7-day CloudWatch consumed read/write capacity. Supports both provisioned
 * (RCU/WCU hourly rates) and on-demand (consumed capacity estimation) billing modes.
 * Delegates to {@link RecommendationEngine#getDynamoDbRecommendation} for classification.</p>
 */
@Component
public class DynamoDbScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(DynamoDbScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public DynamoDbScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans DynamoDB tables in the given region.
     *
     * <p>Calls {@code listTablesPaginator} and {@code describeTable} per table, then queries
     * CloudWatch consumed read/write capacity. Errors on individual tables are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered tables with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var dynamo = ReadOnlyAwsClientFactory.build(DynamoDbClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            List<String> tableNames = new ArrayList<>();
            dynamo.listTablesPaginator().tableNames().forEach(tableNames::add);

            for (String tableName : tableNames) {
                try {
                    TableDescription table = dynamo.describeTable(DescribeTableRequest.builder().tableName(tableName).build()).table();
                    results.add(this.buildDto(table, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process DynamoDB table {}: {}", tableName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("DynamoDB scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single DynamoDB table.
     *
     * <p>Determines billing mode (provisioned vs on-demand) and fetches 7-day consumed
     * read/write capacity from CloudWatch. Provisioned tables use RCU/WCU hourly rates;
     * on-demand tables estimate from consumed capacity.</p>
     */
    private ResourceDto buildDto(TableDescription table, CloudWatchClient cw, String region) {
        String tableName = table.tableName();
        BillingMode billingMode = table.billingModeSummary() != null ? table.billingModeSummary().billingMode() : BillingMode.PROVISIONED;
        String billingLabel = billingMode == BillingMode.PAY_PER_REQUEST ? "On-Demand" : "Provisioned";
        ProvisionedThroughputDescription throughput = table.provisionedThroughput();
        long rcu = throughput != null ? throughput.readCapacityUnits() : 0L;
        long wcu = throughput != null ? throughput.writeCapacityUnits() : 0L;
        double consumedRead = this.getConsumedCapacity(cw, tableName, "ConsumedReadCapacityUnits");
        double consumedWrite = this.getConsumedCapacity(cw, tableName, "ConsumedWriteCapacityUnits");
        double cost;
        if (billingMode == BillingMode.PAY_PER_REQUEST) {
            cost = this.pricingService.getDynamoDbOnDemandPrice(consumedRead, consumedWrite, region);
        } else {
            cost = this.pricingService.getDynamoDbPrice(rcu, wcu, region);
        }
        String recommendation = this.engine.getDynamoDbRecommendation(consumedRead, consumedWrite);
        String instanceType = billingLabel + " (RCU: " + rcu + ", WCU: " + wcu + ")";
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("DynamoDB");
        dto.setResourceId(tableName);
        dto.setResourceName(tableName);
        dto.setInstanceType(instanceType);
        dto.setState(table.tableStatusAsString());
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (table.creationDateTime() != null) {
            dto.setCreatedDate(table.creationDateTime().toString());
        }

        return dto;
    }

    private double getConsumedCapacity(CloudWatchClient cw, String tableName, String metricName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/DynamoDB").metricName(metricName).dimensions(Dimension.builder().name("TableName").value(tableName).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().isEmpty() ? 0.0 : response.datapoints().stream().mapToDouble(Datapoint::sum).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch {} query failed for {}: {}", metricName, tableName, e.getMessage());
            return 0.0;
        }
    }
}
