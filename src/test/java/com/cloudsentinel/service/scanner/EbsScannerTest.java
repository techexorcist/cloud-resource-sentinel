package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;
import software.amazon.awssdk.services.ec2.model.VolumeAttachment;
import software.amazon.awssdk.services.ec2.model.VolumeState;
import software.amazon.awssdk.services.ec2.model.VolumeType;
import software.amazon.awssdk.services.ec2.paginators.DescribeVolumesIterable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EbsScannerTest {

    @Mock
    PricingService pricingService;

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void scan_availableVolume_returnsDeleteUnattached() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockEc2);

            Volume volume = Volume.builder()
                    .volumeId("vol-unattached01")
                    .volumeType(VolumeType.GP3)
                    .size(100)
                    .state(VolumeState.AVAILABLE)
                    .tags(Tag.builder().key("Name").value("orphan-vol").build())
                    .attachments(List.of())
                    .createTime(Instant.now())
                    .build();

            var paginator = mock(DescribeVolumesIterable.class);
            when(mockEc2.describeVolumesPaginator()).thenReturn(paginator);
            when(paginator.volumes()).thenReturn(() -> List.of(volume).iterator());

            when(pricingService.getEbsPrice(100, "gp3", "us-east-1")).thenReturn(8.00);

            var scanner = new EbsScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("EBS", dto.getResourceType());
            assertEquals("vol-unattached01", dto.getResourceId());
            assertEquals("orphan-vol", dto.getResourceName());
            assertEquals("available", dto.getState());
            assertEquals(8.00, dto.getMonthlyCostUsd());
            assertEquals("Delete - Unattached", dto.getRecommendation());
        }
    }

    @Test
    void scan_inUseVolume_returnsInUse() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockEc2 = mock(Ec2Client.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockEc2);

            VolumeAttachment attachment = VolumeAttachment.builder()
                    .instanceId("i-abc123")
                    .state("attached")
                    .build();

            Volume volume = Volume.builder()
                    .volumeId("vol-attached02")
                    .volumeType(VolumeType.GP3)
                    .size(50)
                    .state(VolumeState.IN_USE)
                    .tags(Tag.builder().key("Name").value("data-vol").build())
                    .attachments(attachment)
                    .createTime(Instant.now())
                    .build();

            var paginator = mock(DescribeVolumesIterable.class);
            when(mockEc2.describeVolumesPaginator()).thenReturn(paginator);
            when(paginator.volumes()).thenReturn(() -> List.of(volume).iterator());

            when(pricingService.getEbsPrice(50, "gp3", "us-east-1")).thenReturn(4.00);

            var scanner = new EbsScanner(pricingService, engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("EBS", dto.getResourceType());
            assertEquals("vol-attached02", dto.getResourceId());
            assertEquals("in-use", dto.getState());
            assertEquals(4.00, dto.getMonthlyCostUsd());
            assertEquals("In Use", dto.getRecommendation());
            assertTrue(dto.getInstanceType().contains("i-abc123"), "Should include attached instance ID");
        }
    }
}
