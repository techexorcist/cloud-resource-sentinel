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
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceState;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.InstanceType;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.paginators.DescribeInstancesIterable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Ec2ScannerTest {

    @Mock
    PricingService pricingService;

    private final RecommendationEngine engine = new RecommendationEngine();

    /**
     * Stubs ReadOnlyAwsClientFactory.build() to return the EC2 client on the first call
     * and the CloudWatch client on the second call, matching the order in Ec2Scanner.scan().
     */
    private void stubFactory(
            org.mockito.MockedStatic<ReadOnlyAwsClientFactory> factory,
            Ec2Client mockEc2, CloudWatchClient mockCw) {
        var callCount = new AtomicInteger(0);
        factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                .thenAnswer(invocation -> callCount.getAndIncrement() == 0 ? mockEc2 : mockCw);
    }

    @Test
    void scan_stoppedInstance_returnsConsiderTerminating() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            var mockCw = mock(CloudWatchClient.class);
            stubFactory(factory, mockEc2, mockCw);

            Instance instance = Instance.builder()
                    .instanceId("i-stopped123")
                    .instanceType(InstanceType.T3_MICRO)
                    .state(InstanceState.builder().name(InstanceStateName.STOPPED).build())
                    .tags(Tag.builder().key("Name").value("stopped-server").build())
                    .launchTime(Instant.now())
                    .build();

            var paginator = mock(DescribeInstancesIterable.class);
            when(mockEc2.describeInstancesPaginator()).thenReturn(paginator);
            var reservation = Reservation.builder().instances(instance).build();
            when(paginator.reservations()).thenReturn(() -> List.of(reservation).iterator());

            // CloudWatch returns empty for stopped instance
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenReturn(GetMetricStatisticsResponse.builder().datapoints(List.of()).build());

            var scanner = new Ec2Scanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("EC2", dto.getResourceType());
            assertEquals("i-stopped123", dto.getResourceId());
            assertEquals("stopped-server", dto.getResourceName());
            assertEquals("stopped", dto.getState());
            assertEquals(0.0, dto.getMonthlyCostUsd());
            assertEquals("Consider Terminating - Stopped", dto.getRecommendation());
        }
    }

    @Test
    void scan_runningLowCpu_returnsIdle() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            var mockCw = mock(CloudWatchClient.class);
            stubFactory(factory, mockEc2, mockCw);

            Instance instance = Instance.builder()
                    .instanceId("i-idle456")
                    .instanceType(InstanceType.M5_LARGE)
                    .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                    .tags(Tag.builder().key("Name").value("idle-server").build())
                    .launchTime(Instant.now())
                    .build();

            var paginator = mock(DescribeInstancesIterable.class);
            when(mockEc2.describeInstancesPaginator()).thenReturn(paginator);
            when(paginator.reservations()).thenReturn(() -> List.of(
                    Reservation.builder().instances(instance).build()).iterator());

            // CloudWatch returns low CPU (2%)
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenReturn(GetMetricStatisticsResponse.builder()
                            .datapoints(Datapoint.builder().average(2.0).build())
                            .build());

            when(pricingService.getEc2Price("m5.large", "us-east-1")).thenReturn(70.08);

            var scanner = new Ec2Scanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("EC2", dto.getResourceType());
            assertEquals("i-idle456", dto.getResourceId());
            assertEquals("running", dto.getState());
            assertEquals(70.08, dto.getMonthlyCostUsd());
            assertEquals("Idle - Consider Downsizing or Terminating", dto.getRecommendation());
        }
    }

    @Test
    void scan_runningHighCpu_returnsActive() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            var mockCw = mock(CloudWatchClient.class);
            stubFactory(factory, mockEc2, mockCw);

            Instance instance = Instance.builder()
                    .instanceId("i-active789")
                    .instanceType(InstanceType.C5_XLARGE)
                    .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                    .tags(Tag.builder().key("Name").value("busy-server").build())
                    .launchTime(Instant.now())
                    .build();

            var paginator = mock(DescribeInstancesIterable.class);
            when(mockEc2.describeInstancesPaginator()).thenReturn(paginator);
            when(paginator.reservations()).thenReturn(() -> List.of(
                    Reservation.builder().instances(instance).build()).iterator());

            // CloudWatch returns high CPU (75%)
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenReturn(GetMetricStatisticsResponse.builder()
                            .datapoints(Datapoint.builder().average(75.0).build())
                            .build());

            when(pricingService.getEc2Price("c5.xlarge", "us-east-1")).thenReturn(124.10);

            var scanner = new Ec2Scanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("EC2", dto.getResourceType());
            assertEquals("i-active789", dto.getResourceId());
            assertEquals("running", dto.getState());
            assertEquals(124.10, dto.getMonthlyCostUsd());
            assertEquals("Active - Good Utilization", dto.getRecommendation());
        }
    }
}
