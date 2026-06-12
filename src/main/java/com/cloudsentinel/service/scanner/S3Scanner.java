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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;

/**
 * Scans S3 buckets for empty, idle, or infrequently accessed buckets.
 *
 * <p>Checks object count, bucket size across 8 storage classes, and recent Get/Put
 * request activity via CloudWatch. Skips size queries for empty buckets. Delegates to
 * {@link RecommendationEngine#getS3Recommendation} for classification.
 * Runs as a global scanner.</p>
 */
@Component
public class S3Scanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(S3Scanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public S3Scanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    /**
     * Scans S3 buckets globally (us-east-1 for listing, per-bucket region for metrics).
     *
     * <p>Calls {@code listBuckets}, resolves each bucket's region via {@code getBucketLocation},
     * then queries CloudWatch for object count, size across 8 storage classes, and
     * Get/Put request activity. Errors on individual buckets are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan (ignored; uses us-east-1 for listing)
     * @return list of discovered S3 buckets with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var s3 = ReadOnlyAwsClientFactory.build(S3Client.builder(), creds, Region.US_EAST_1)) {
            for (Bucket bucket : s3.listBuckets().buckets()) {
                try {
                    String bucketRegion = getBucketRegion(s3, bucket.name());
                    try (var cw = ReadOnlyAwsClientFactory.build(
                            CloudWatchClient.builder(), creds, Region.of(bucketRegion))) {
                        results.add(buildDto(bucket, cw, bucketRegion));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process S3 bucket {}: {}", bucket.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("S3 scan failed: {}", e.getMessage());
        }

        return results;
    }

    private String getBucketRegion(S3Client s3, String bucketName) {
        try {
            String location = s3.getBucketLocation(GetBucketLocationRequest.builder()
                    .bucket(bucketName).build()).locationConstraintAsString();
            return location != null && !location.isEmpty() && !"null".equals(location) ? location : "us-east-1";
        } catch (Exception e) {
            log.debug("Failed to get location for bucket {}: {}", bucketName, e.getMessage());
            return "us-east-1";
        }
    }

    /**
     * Builds a ResourceDto for a single S3 bucket.
     *
     * <p>Fetches object count, bucket size (skipping size query for empty buckets),
     * and 30-day Get/Put request activity from CloudWatch. Cost is based on total
     * storage in GB.</p>
     */
    private ResourceDto buildDto(Bucket bucket, CloudWatchClient cw, String bucketRegion) {
        String bucketName = bucket.name();
        long objectCount = getNumberOfObjects(cw, bucketName);
        double sizeBytes = objectCount > 0 ? getBucketSizeBytes(cw, bucketName) : 0.0;
        double sizeGb = sizeBytes / 1_073_741_824.0;
        double pricePerGb = pricingService.getS3PricePerGb(bucketRegion);
        double cost = Math.round(sizeGb * pricePerGb * 100.0) / 100.0;
        boolean recentAccess = hasRecentAccess(cw, bucketName);
        String recommendation = engine.getS3Recommendation(objectCount, recentAccess);

        var dto = new ResourceDto();
        dto.setRegion(bucketRegion);
        dto.setResourceType("S3");
        dto.setResourceId(bucketName);
        dto.setResourceName(bucketName);
        dto.setInstanceType(String.format("%.2f GB / %d objects", sizeGb, objectCount));
        dto.setState("active");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (bucket.creationDate() != null) {
            dto.setCreatedDate(bucket.creationDate().toString());
        }
        return dto;
    }

    private long getNumberOfObjects(CloudWatchClient cw, String bucketName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(2, ChronoUnit.DAYS);
            GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder()
                    .namespace("AWS/S3").metricName("NumberOfObjects")
                    .dimensions(Dimension.builder().name("BucketName").value(bucketName).build(),
                            Dimension.builder().name("StorageType").value("AllStorageTypes").build())
                    .startTime(start).endTime(end).period(86400)
                    .statistics(Statistic.AVERAGE).build());
            return response.datapoints().stream().mapToLong(dp -> dp.average().longValue()).max().orElse(0L);
        } catch (Exception e) {
            log.debug("CloudWatch NumberOfObjects query failed for {}: {}", bucketName, e.getMessage());
            return 0L;
        }
    }

    private boolean hasRecentAccess(CloudWatchClient cw, String bucketName) {
        try {
            Instant end = Instant.now();
            Instant start = end.minus(30, ChronoUnit.DAYS);
            double totalRequests = 0.0;

            for (String metricName : List.of("GetRequests", "PutRequests")) {
                GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder()
                        .namespace("AWS/S3").metricName(metricName)
                        .dimensions(Dimension.builder().name("BucketName").value(bucketName).build(),
                                Dimension.builder().name("FilterId").value("EntireBucket").build())
                        .startTime(start).endTime(end).period(86400)
                        .statistics(Statistic.SUM).build());
                totalRequests += response.datapoints().stream().mapToDouble(Datapoint::sum).sum();
            }

            return totalRequests > 0.0;
        } catch (Exception e) {
            log.debug("CloudWatch request metrics query failed for {}: {}", bucketName, e.getMessage());
            return false;
        }
    }

    private double getBucketSizeBytes(CloudWatchClient cw, String bucketName) {
        double totalBytes = 0.0;
        for (String storageType : List.of("StandardStorage", "StandardIAStorage", "GlacierStorage",
                "DeepArchiveStorage", "IntelligentTieringStorage", "OneZoneIAStorage",
                "GlacierIRStorage", "ReducedRedundancyStorage")) {
            try {
                Instant end = Instant.now();
                Instant start = end.minus(2, ChronoUnit.DAYS);
                GetMetricStatisticsResponse response = cw.getMetricStatistics(GetMetricStatisticsRequest.builder()
                        .namespace("AWS/S3").metricName("BucketSizeBytes")
                        .dimensions(Dimension.builder().name("BucketName").value(bucketName).build(),
                                Dimension.builder().name("StorageType").value(storageType).build())
                        .startTime(start).endTime(end).period(86400)
                        .statistics(Statistic.AVERAGE).build());
                totalBytes += response.datapoints().stream().mapToDouble(Datapoint::average).max().orElse(0.0);
            } catch (Exception e) {
                log.debug("CloudWatch BucketSizeBytes query failed for {}/{}: {}", bucketName, storageType, e.getMessage());
            }
        }
        return totalBytes;
    }
}
