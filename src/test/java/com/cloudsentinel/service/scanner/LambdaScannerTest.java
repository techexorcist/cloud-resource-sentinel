package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

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
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionConfiguration;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.paginators.ListFunctionsIterable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LambdaScannerTest {

    @Mock
    PricingService pricingService;

    private final RecommendationEngine engine = new RecommendationEngine();

    /**
     * Stubs ReadOnlyAwsClientFactory.build() to return the Lambda client on the first call
     * and the CloudWatch client on the second call, matching the order in LambdaScanner.scan().
     */
    private void stubFactory(
            org.mockito.MockedStatic<ReadOnlyAwsClientFactory> factory,
            LambdaClient mockLambda, CloudWatchClient mockCw) {
        var callCount = new AtomicInteger(0);
        factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                .thenAnswer(invocation -> callCount.getAndIncrement() == 0 ? mockLambda : mockCw);
    }

    @Test
    void scan_zeroInvocations_returnsIdle() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockLambda = mock(LambdaClient.class);
            var mockCw = mock(CloudWatchClient.class);
            stubFactory(factory, mockLambda, mockCw);

            FunctionConfiguration fn = FunctionConfiguration.builder()
                    .functionName("idle-processor")
                    .functionArn("arn:aws:lambda:us-east-1:123456789:function:idle-processor")
                    .runtime(Runtime.PYTHON3_12)
                    .memorySize(128)
                    .lastModified("2025-01-01T00:00:00Z")
                    .build();

            var paginator = mock(ListFunctionsIterable.class);
            when(mockLambda.listFunctionsPaginator()).thenReturn(paginator);
            when(paginator.functions()).thenReturn(() -> List.of(fn).iterator());

            // All CloudWatch queries return empty (zero invocations, zero errors, zero duration)
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenReturn(GetMetricStatisticsResponse.builder().datapoints(List.of()).build());

            when(pricingService.getLambdaPrice(0L, 0L, 128, "us-east-1")).thenReturn(0.0);

            var scanner = new LambdaScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("Lambda", dto.getResourceType());
            assertEquals("idle-processor", dto.getResourceName());
            assertEquals("idle", dto.getState());
            assertEquals(0.0, dto.getMonthlyCostUsd());
            assertEquals("Idle - No Invocations", dto.getRecommendation());
        }
    }

    @Test
    void scan_activeFunction_returnsActive() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockLambda = mock(LambdaClient.class);
            var mockCw = mock(CloudWatchClient.class);
            stubFactory(factory, mockLambda, mockCw);

            FunctionConfiguration fn = FunctionConfiguration.builder()
                    .functionName("busy-handler")
                    .functionArn("arn:aws:lambda:us-east-1:123456789:function:busy-handler")
                    .runtime(Runtime.JAVA21)
                    .memorySize(512)
                    .lastModified("2025-06-01T00:00:00Z")
                    .build();

            var paginator = mock(ListFunctionsIterable.class);
            when(mockLambda.listFunctionsPaginator()).thenReturn(paginator);
            when(paginator.functions()).thenReturn(() -> List.of(fn).iterator());

            // Return high invocations (500/day sum), low errors, some duration
            when(mockCw.getMetricStatistics(any(GetMetricStatisticsRequest.class)))
                    .thenAnswer(invocation -> {
                        GetMetricStatisticsRequest req = invocation.getArgument(0);
                        String metricName = req.metricName();
                        if ("Invocations".equals(metricName)) {
                            return GetMetricStatisticsResponse.builder()
                                    .datapoints(Datapoint.builder().sum(500.0).average(500.0).build())
                                    .build();
                        } else if ("Errors".equals(metricName)) {
                            return GetMetricStatisticsResponse.builder()
                                    .datapoints(Datapoint.builder().sum(1.0).average(1.0).build())
                                    .build();
                        } else if ("Duration".equals(metricName)) {
                            return GetMetricStatisticsResponse.builder()
                                    .datapoints(Datapoint.builder().average(200.0).sum(200.0).build())
                                    .build();
                        }
                        return GetMetricStatisticsResponse.builder().datapoints(List.of()).build();
                    });

            when(pricingService.getLambdaPrice(15000L, 200L, 512, "us-east-1")).thenReturn(0.50);

            var scanner = new LambdaScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("Lambda", dto.getResourceType());
            assertEquals("busy-handler", dto.getResourceName());
            assertEquals("active", dto.getState());
            assertEquals("Active", dto.getRecommendation());
        }
    }
}
