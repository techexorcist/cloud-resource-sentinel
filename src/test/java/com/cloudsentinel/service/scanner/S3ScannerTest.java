package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketLocationConstraint;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3ScannerTest {

    @Mock
    PricingService pricingService;

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void scan_emptyBucket_returnsEmptyConsiderDeleting() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockS3 = mock(S3Client.class);
            var mockCw = mock(CloudWatchClient.class);

            // S3Scanner calls build() first for S3Client, then for each bucket's CloudWatchClient
            var callCount = new AtomicInteger(0);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenAnswer(invocation -> callCount.getAndIncrement() == 0 ? mockS3 : mockCw);

            Bucket bucket = Bucket.builder()
                    .name("empty-test-bucket")
                    .creationDate(Instant.now())
                    .build();

            when(mockS3.listBuckets()).thenReturn(ListBucketsResponse.builder().buckets(bucket).build());
            when(mockS3.getBucketLocation(any(GetBucketLocationRequest.class)))
                    .thenReturn(GetBucketLocationResponse.builder()
                            .locationConstraint(BucketLocationConstraint.US_WEST_2)
                            .build());

            // NumberOfObjects returns 0 (empty datapoints)
            // All CloudWatch queries return empty
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenReturn(GetMetricStatisticsResponse.builder().datapoints(List.of()).build());

            when(pricingService.getS3PricePerGb("us-west-2")).thenReturn(0.023);

            var scanner = new S3Scanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("S3", dto.getResourceType());
            assertEquals("empty-test-bucket", dto.getResourceId());
            assertEquals("empty-test-bucket", dto.getResourceName());
            assertEquals(0.0, dto.getMonthlyCostUsd());
            assertEquals("Empty - Consider Deleting", dto.getRecommendation());
            assertTrue(dto.getInstanceType().contains("0 objects"));
        }
    }
}
