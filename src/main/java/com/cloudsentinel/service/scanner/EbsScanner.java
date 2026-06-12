package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;

/**
 * Scans EBS volumes for unattached or available volumes that incur storage costs.
 *
 * <p>Checks volume state and attachment status. Includes attached instance ID for
 * cross-resource correlation. Delegates to {@link RecommendationEngine#getEbsRecommendation}
 * for classification.</p>
 */
@Component
public class EbsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EbsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public EbsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans EBS volumes in the given region.
     *
     * <p>Calls {@code describeVolumesPaginator} and iterates over all volumes.
     * Errors on individual volumes are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered EBS volumes with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region))) {

            ec2.describeVolumesPaginator().volumes().stream().forEach(volume -> {
                try {
                    results.add(buildDto(volume, region));
                } catch (Exception e) {
                    log.warn("Failed to process EBS volume {}: {}", volume.volumeId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("EBS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single EBS volume.
     *
     * <p>Populates volume type, size, state, and attached instance ID for cross-resource
     * correlation. Cost is based on volume type and size in GB.</p>
     */
    private ResourceDto buildDto(Volume volume, String region) {
        String volumeId = volume.volumeId();
        String volumeType = volume.volumeTypeAsString();
        int size = volume.size();
        String state = volume.stateAsString();
        String name = volume.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(volumeId);
        double cost = pricingService.getEbsPrice(size, volumeType, region);
        String recommendation = engine.getEbsRecommendation(state);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("EBS");
        dto.setResourceId(volumeId);
        dto.setResourceName(name);
        // Include attached instance ID for cross-resource correlation
        String attachedTo = volume.attachments().stream()
                .filter(a -> "attached".equals(a.stateAsString()))
                .map(a -> a.instanceId())
                .findFirst().orElse(null);
        String instanceDesc = volumeType + " (" + size + " GB)";
        if (attachedTo != null) {
            instanceDesc += " / " + attachedTo;
        }
        dto.setInstanceType(instanceDesc);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (volume.createTime() != null) {
            dto.setCreatedDate(volume.createTime().toString());
        }
        return dto;
    }
}
