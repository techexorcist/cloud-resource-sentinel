package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Scans SQS queues for idle or low-traffic queues.
 *
 * <p>Checks 7-day CloudWatch messages-sent rate and approximate message count.
 * Delegates to {@link RecommendationEngine#getSqsRecommendation} for classification.</p>
 */
@Component
public class SqsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(SqsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public SqsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans SQS queues in the given region.
     *
     * <p>Calls {@code listQueues}, {@code getQueueAttributes} per queue, and queries
     * CloudWatch NumberOfMessagesSent. Errors on individual queues are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered SQS queues with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var sqs = ReadOnlyAwsClientFactory.build(SqsClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            for (String queueUrl : sqs.listQueues().queueUrls()) {
                try {
                    results.add(this.buildDto(sqs, cw, queueUrl, region));
                } catch (Exception e) {
                    log.warn("Failed to process SQS queue {}: {}", queueUrl, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("SQS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single SQS queue.
     *
     * <p>Fetches approximate message count and 7-day average daily messages-sent rate.
     * Cost is based on estimated monthly message volume.</p>
     */
    private ResourceDto buildDto(SqsClient sqs, CloudWatchClient cw, String queueUrl, String region) {
        Map<QueueAttributeName, String> attrs = sqs.getQueueAttributes((GetQueueAttributesRequest) GetQueueAttributesRequest.builder().queueUrl(queueUrl).attributeNames(new QueueAttributeName[]{QueueAttributeName.ALL}).build()).attributes();
        String queueName = queueUrl.substring(queueUrl.lastIndexOf(47) + 1);
        long approxMessages = Long.parseLong((String) attrs.getOrDefault(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "0"));
        String createdTimestamp = (String) attrs.get(QueueAttributeName.CREATED_TIMESTAMP);
        double messagesSentPerDay = this.getMetricAverage(cw, queueName, "NumberOfMessagesSent");
        String recommendation = this.engine.getSqsRecommendation(messagesSentPerDay);
        long estimatedMonthlyMessages = (long) (messagesSentPerDay * 30.0);
        double cost = this.pricingService.getSqsPrice(estimatedMonthlyMessages, region);
        String description = String.format("Pending: %d / Sent/day: %.0f", approxMessages, messagesSentPerDay);
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("SQS");
        dto.setResourceId(queueName);
        dto.setResourceName(queueName);
        dto.setInstanceType(description);
        dto.setState(approxMessages > 0L ? "active" : "empty");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (createdTimestamp != null) {
            dto.setCreatedDate(Instant.ofEpochSecond(Long.parseLong(createdTimestamp)).toString());
        }

        return dto;
    }

    private double getMetricAverage(CloudWatchClient cw, String queueName, String metricName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/SQS").metricName(metricName).dimensions(Dimension.builder().name("QueueName").value(queueName).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch {} query failed for SQS {}: {}", metricName, queueName, e.getMessage());
            return 0.0;
        }
    }
}
