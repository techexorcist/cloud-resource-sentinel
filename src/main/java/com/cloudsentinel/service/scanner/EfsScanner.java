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
import software.amazon.awssdk.services.efs.EfsClient;
import software.amazon.awssdk.services.efs.model.FileSystemDescription;
import software.amazon.awssdk.services.efs.model.Tag;

/**
 * Scans EFS file systems for empty or unmounted file systems.
 *
 * <p>Checks file system size and mount target count. Flags file systems with zero
 * mount targets or zero stored bytes. Delegates to
 * {@link RecommendationEngine#getEfsRecommendation} for classification.</p>
 */
@Component
public class EfsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EfsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public EfsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans EFS file systems in the given region.
     *
     * <p>Calls {@code describeFileSystems} and {@code describeMountTargets} per file system.
     * Errors on individual file systems are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered EFS file systems with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var efs = ReadOnlyAwsClientFactory.build(EfsClient.builder(), creds, Region.of(region))) {

            for (FileSystemDescription fs : efs.describeFileSystems().fileSystems()) {
                try {
                    results.add(buildDto(fs, efs, region));
                } catch (Exception e) {
                    log.warn("Failed to process EFS {}: {}", fs.fileSystemId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("EFS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single EFS file system.
     *
     * <p>Computes size in GB, checks mount target count, and reports performance/throughput
     * mode. File systems with zero mount targets or zero bytes are flagged.</p>
     */
    private ResourceDto buildDto(FileSystemDescription fs, EfsClient efs, String region) {
        String fileSystemId = fs.fileSystemId();
        String state = fs.lifeCycleStateAsString();
        long sizeInBytes = fs.sizeInBytes() != null ? fs.sizeInBytes().value() : 0L;
        double sizeGb = (double) sizeInBytes / 1_073_741_824.0;
        String performanceMode = fs.performanceModeAsString();
        String throughputMode = fs.throughputModeAsString();
        String name = fs.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(fileSystemId);
        int mountTargetCount = efs.describeMountTargets(r -> r.fileSystemId(fileSystemId)).mountTargets().size();
        String recommendation = engine.getEfsRecommendation(sizeInBytes, mountTargetCount);

        double cost = pricingService.getEfsPrice(sizeGb, region);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("EFS");
        dto.setResourceId(fileSystemId);
        dto.setResourceName(name);
        dto.setInstanceType(String.format("%.2fGB / %s / %s", sizeGb, performanceMode, throughputMode));
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (fs.creationTime() != null) {
            dto.setCreatedDate(fs.creationTime().toString());
        }
        return dto;
    }
}
