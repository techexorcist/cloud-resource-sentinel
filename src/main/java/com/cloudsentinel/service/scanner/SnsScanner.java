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
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.GetTopicAttributesRequest;
import software.amazon.awssdk.services.sns.model.Topic;

/**
 * Scans SNS topics for idle or subscriber-less topics.
 *
 * <p>Checks confirmed subscription count and 7-day CloudWatch publish rate.
 * Delegates to {@link RecommendationEngine#getSnsRecommendation} for classification.</p>
 */
@Component
public class SnsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(SnsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public SnsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans SNS topics in the given region.
     *
     * <p>Calls {@code listTopicsPaginator}, {@code getTopicAttributes} per topic, and
     * queries CloudWatch NumberOfMessagesPublished. Errors on individual topics are
     * logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered SNS topics with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var sns = ReadOnlyAwsClientFactory.build(SnsClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            sns.listTopicsPaginator().topics().forEach((topic) -> {
                try {
                    results.add(this.buildDto(sns, cw, topic, region));
                } catch (Exception e) {
                    log.warn("Failed to process SNS topic {}: {}", topic.topicArn(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("SNS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single SNS topic.
     *
     * <p>Fetches confirmed subscription count and 7-day average daily publish rate.
     * Cost is based on estimated monthly publishes.</p>
     */
    private ResourceDto buildDto(SnsClient sns, CloudWatchClient cw, Topic topic, String region) {
        String topicArn = topic.topicArn();
        String topicName = topicArn.substring(topicArn.lastIndexOf(58) + 1);
        Map<String, String> attrs = sns.getTopicAttributes((GetTopicAttributesRequest) GetTopicAttributesRequest.builder().topicArn(topicArn).build()).attributes();
        int subscriptionCount = Integer.parseInt((String) attrs.getOrDefault("SubscriptionsConfirmed", "0"));
        double publishesPerDay = this.getMetricAverage(cw, topicName, "NumberOfMessagesPublished");
        String recommendation = this.engine.getSnsRecommendation(subscriptionCount, publishesPerDay);
        long estimatedMonthlyPublishes = (long) (publishesPerDay * 30.0);
        double cost = this.pricingService.getSnsPrice(estimatedMonthlyPublishes, region);
        String description = String.format("Subs: %d / Publishes/day: %.0f", subscriptionCount, publishesPerDay);
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("SNS");
        dto.setResourceId(topicArn);
        dto.setResourceName(topicName);
        dto.setInstanceType(description);
        dto.setState(subscriptionCount > 0 ? "active" : "no-subscribers");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }

    private double getMetricAverage(CloudWatchClient cw, String topicName, String metricName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/SNS").metricName(metricName).dimensions(Dimension.builder().name("TopicName").value(topicName).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).average().orElse(0.0);
        } catch (Exception e) {
            log.debug("CloudWatch {} query failed for SNS {}: {}", metricName, topicName, e.getMessage());
            return 0.0;
        }
    }
}
