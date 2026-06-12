package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.DescribeAddressesResponse;
import software.amazon.awssdk.services.ec2.model.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticIpScannerTest {

    @Mock
    PricingService pricingService;

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void scan_unassociatedIp_returnsReleaseUnattached() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockEc2);

            Address address = Address.builder()
                    .publicIp("192.0.2.1")
                    .allocationId("eipalloc-orphan01")
                    .tags(Tag.builder().key("Name").value("unused-eip").build())
                    // No associationId = unassociated
                    .build();

            when(mockEc2.describeAddresses())
                    .thenReturn(DescribeAddressesResponse.builder().addresses(address).build());

            when(pricingService.getElasticIpPrice("us-east-1")).thenReturn(3.65);

            var scanner = new ElasticIpScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("Elastic IP", dto.getResourceType());
            assertEquals("eipalloc-orphan01", dto.getResourceId());
            assertEquals("unused-eip", dto.getResourceName());
            assertEquals("unattached", dto.getState());
            assertEquals(3.65, dto.getMonthlyCostUsd());
            assertEquals("Release - Unattached", dto.getRecommendation());
        }
    }

    @Test
    void scan_associatedIp_returnsInUse() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockEc2);

            Address address = Address.builder()
                    .publicIp("192.0.2.2")
                    .allocationId("eipalloc-attached02")
                    .associationId("eipassoc-abc123")
                    .instanceId("i-webserver01")
                    .tags(Tag.builder().key("Name").value("web-eip").build())
                    .build();

            when(mockEc2.describeAddresses())
                    .thenReturn(DescribeAddressesResponse.builder().addresses(address).build());

            when(pricingService.getElasticIpPrice("us-east-1")).thenReturn(3.65);

            var scanner = new ElasticIpScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("Elastic IP", dto.getResourceType());
            assertEquals("eipalloc-attached02", dto.getResourceId());
            assertEquals("associated", dto.getState());
            assertEquals(3.65, dto.getMonthlyCostUsd());
            assertEquals("In Use", dto.getRecommendation());
        }
    }
}
