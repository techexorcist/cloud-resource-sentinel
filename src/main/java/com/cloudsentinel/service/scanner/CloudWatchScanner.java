package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.FindingType;
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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;

/**
 * Scans CloudWatch alarms and log groups for governance issues.
 *
 * <p>Checks alarm state for insufficient-data conditions and log group retention
 * policy (flags groups with no expiration). Delegates to
 * {@link RecommendationEngine#getCloudWatchAlarmRecommendation} and
 * {@link RecommendationEngine#getCloudWatchLogGroupRecommendation} for classification.</p>
 */
@Component
public class CloudWatchScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(CloudWatchScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public CloudWatchScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.GOVERNANCE;
    }

    /**
     * Scans CloudWatch alarms and log groups in the given region.
     *
     * <p>Calls {@code describeAlarmsPaginator} for metric alarms and
     * {@code describeLogGroupsPaginator} for log groups. Errors on individual
     * alarms or log groups are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered alarms and log groups with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {
            cw.describeAlarmsPaginator().metricAlarms().forEach((alarm) -> {
                try {
                    results.add(this.buildAlarmDto(alarm, region));
                } catch (Exception e) {
                    log.warn("Failed to process CW alarm {}: {}", alarm.alarmName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("CloudWatch alarm scan failed in region {}: {}", region, e.getMessage());
        }

        try (var logs = ReadOnlyAwsClientFactory.build(CloudWatchLogsClient.builder(), creds, Region.of(region))) {
            logs.describeLogGroupsPaginator().logGroups().forEach((logGroup) -> {
                try {
                    results.add(this.buildLogGroupDto(logGroup, region));
                } catch (Exception e) {
                    log.warn("Failed to process log group {}: {}", logGroup.logGroupName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("CloudWatch Logs scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single CloudWatch alarm.
     *
     * <p>Populates alarm state, namespace, metric name, and comparison operator.
     * Cost is per-alarm from PricingService.</p>
     */
    private ResourceDto buildAlarmDto(MetricAlarm alarm, String region) {
        String stateValue = alarm.stateValueAsString();
        String recommendation = this.engine.getCloudWatchAlarmRecommendation(stateValue);
        String description = String.format("%s / %s %s %s", alarm.namespace() != null ? alarm.namespace() : "custom", alarm.metricName() != null ? alarm.metricName() : "", alarm.comparisonOperatorAsString(), alarm.threshold());
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("CloudWatch Alarm");
        dto.setResourceId(alarm.alarmArn());
        dto.setResourceName(alarm.alarmName());
        dto.setInstanceType(description);
        dto.setState(stateValue);
        dto.setMonthlyCostUsd(this.pricingService.getCloudWatchAlarmPrice(1, region));
        dto.setRecommendation(recommendation);
        return dto;
    }

    /**
     * Builds a ResourceDto for a single CloudWatch log group.
     *
     * <p>Computes stored size in GB and checks retention policy. Cost is based on
     * stored data volume.</p>
     */
    private ResourceDto buildLogGroupDto(LogGroup logGroup, String region) {
        Integer retentionDays = logGroup.retentionInDays();
        long storedBytes = logGroup.storedBytes() != null ? logGroup.storedBytes() : 0L;
        double storedGb = (double) storedBytes / 1_073_741_824.0;
        String recommendation = this.engine.getCloudWatchLogGroupRecommendation(retentionDays);
        double cost = this.pricingService.getCloudWatchLogStoragePrice(storedGb, region);
        String description = String.format("%.2f GB / Retention: %s", storedGb, retentionDays != null ? retentionDays + " days" : "never expires");
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("CloudWatch Log Group");
        dto.setResourceId(logGroup.arn());
        dto.setResourceName(logGroup.logGroupName());
        dto.setInstanceType(description);
        dto.setState(retentionDays != null ? "retention-set" : "no-retention");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }
}
