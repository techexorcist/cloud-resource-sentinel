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
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.StreamDescriptionSummary;

/**
 * Scans Kinesis data streams for idle or low-throughput streams.
 *
 * <p>Checks 7-day CloudWatch IncomingRecords count and stream status. Prices
 * based on open shard count. Delegates to
 * {@link RecommendationEngine#getKinesisRecommendation} for classification.</p>
 */
@Component
public class KinesisScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(KinesisScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public KinesisScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Kinesis data streams in the given region.
     *
     * <p>Calls {@code listStreams} and {@code describeStreamSummary} per stream, then
     * queries CloudWatch IncomingRecords. Errors on individual streams are logged
     * and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Kinesis streams with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var kinesis = ReadOnlyAwsClientFactory.build(KinesisClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            for (String streamName : kinesis.listStreams().streamNames()) {
                try {
                    StreamDescriptionSummary summary = kinesis.describeStreamSummary(r -> r.streamName(streamName)).streamDescriptionSummary();
                    results.add(buildDto(summary, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process Kinesis stream {}: {}", streamName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Kinesis scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Kinesis data stream.
     *
     * <p>Populates shard count, retention period, and 7-day incoming record count.
     * Cost is based on open shard count.</p>
     */
    private ResourceDto buildDto(StreamDescriptionSummary summary, CloudWatchClient cw, String region) {
        String streamName = summary.streamName();
        int shardCount = summary.openShardCount();
        int retentionPeriod = summary.retentionPeriodHours();
        String status = summary.streamStatusAsString();
        String description = shardCount + " shards / " + retentionPeriod + "hr retention";
        double incomingRecords = getIncomingRecords(cw, streamName);
        String recommendation = engine.getKinesisRecommendation(status, incomingRecords);

        double cost = pricingService.getKinesisShardPrice(shardCount, region);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Kinesis");
        dto.setResourceId(summary.streamARN());
        dto.setResourceName(streamName);
        dto.setInstanceType(description);
        dto.setState(status);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }

    private double getIncomingRecords(CloudWatchClient cw, String streamName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(7L, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder().namespace("AWS/Kinesis").metricName("IncomingRecords").dimensions(Dimension.builder().name("StreamName").value(streamName).build()).startTime(start).endTime(end).period(86400).statistics(Statistic.SUM).build());
            return response.datapoints().stream().mapToDouble(Datapoint::sum).sum();
        } catch (Exception e) {
            log.debug("CloudWatch IncomingRecords query failed for Kinesis stream {}: {}", streamName, e.getMessage());
            return 0.0;
        }
    }
}
