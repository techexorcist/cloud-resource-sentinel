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
import software.amazon.awssdk.services.fsx.FSxClient;
import software.amazon.awssdk.services.fsx.model.FileSystem;
import software.amazon.awssdk.services.fsx.model.Tag;

/**
 * Scans Amazon FSx file systems for idle or underutilized storage.
 *
 * <p>Checks lifecycle state and storage capacity. Uses type-based per-GB rates
 * (Windows, Lustre, OpenZFS, ONTAP). Delegates to
 * {@link RecommendationEngine#getFsxRecommendation} for classification.</p>
 */
@Component
public class FsxScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(FsxScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public FsxScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans FSx file systems in the given region.
     *
     * <p>Calls {@code describeFileSystems} and iterates over all file systems.
     * Errors on individual file systems are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered FSx file systems with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var fsx = ReadOnlyAwsClientFactory.build(FSxClient.builder(), creds, Region.of(region))) {

            for (FileSystem fs : fsx.describeFileSystems().fileSystems()) {
                try {
                    results.add(buildDto(fs, region));
                } catch (Exception e) {
                    log.warn("Failed to process FSx file system {}: {}", fs.fileSystemId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("FSx scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single FSx file system.
     *
     * <p>Uses type-based per-GB rates (Lustre $0.14, OpenZFS $0.09, ONTAP $0.10,
     * Windows $0.13). Populates file system type, storage capacity, and lifecycle state.</p>
     */
    private ResourceDto buildDto(FileSystem fs, String region) {
        String fileSystemId = fs.fileSystemId();
        String lifecycle = fs.lifecycleAsString();
        String fileSystemType = fs.fileSystemTypeAsString();
        int storageCapacity = fs.storageCapacity() != null ? fs.storageCapacity() : 0;
        String storageType = fs.storageTypeAsString();
        String name = fs.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(fileSystemId);
        String description = fileSystemType + " / " + storageCapacity + "GB / " + storageType;
        String recommendation = engine.getFsxRecommendation(lifecycle, storageCapacity);

        double fsxPerGb = switch (fileSystemType) {
            case "LUSTRE" -> 0.14;
            case "OPENZFS" -> 0.09;
            case "ONTAP" -> 0.10;
            default -> 0.13; // WINDOWS
        };
        double cost = storageCapacity * fsxPerGb;
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("FSx");
        dto.setResourceId(fileSystemId);
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(lifecycle);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (fs.creationTime() != null) {
            dto.setCreatedDate(fs.creationTime().toString());
        }
        return dto;
    }
}
