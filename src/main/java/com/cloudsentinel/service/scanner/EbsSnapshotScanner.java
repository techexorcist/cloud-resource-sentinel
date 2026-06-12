package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Snapshot;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Scans EBS snapshots for orphaned or aged snapshots.
 *
 * <p>Checks snapshot age (flags over 90 days) and whether the source volume still exists.
 * Uses paginator for accounts with more than 1000 snapshots. Delegates to
 * {@link RecommendationEngine#getEbsSnapshotRecommendation} for classification.</p>
 */
@Component
public class EbsSnapshotScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EbsSnapshotScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public EbsSnapshotScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans EBS snapshots owned by the account in the given region.
     *
     * <p>Calls {@code describeSnapshotsPaginator} with owner filter "self". Uses paginator
     * to handle accounts with more than 1000 snapshots. Errors on individual snapshots
     * are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered snapshots with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region))) {

            for (Snapshot snapshot : ec2.describeSnapshotsPaginator(r -> r.ownerIds("self")).snapshots()) {
                try {
                    results.add(buildDto(snapshot, ec2, region));
                } catch (Exception e) {
                    log.warn("Failed to process EBS Snapshot {}: {}", snapshot.snapshotId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("EBS Snapshot scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single EBS snapshot.
     *
     * <p>Checks snapshot age (flags over 90 days) and whether the source volume still
     * exists via {@code describeVolumes}. Orphaned snapshots get elevated recommendations.</p>
     */
    private ResourceDto buildDto(Snapshot snapshot, Ec2Client ec2, String region) {
        String snapshotId = snapshot.snapshotId();
        String state = snapshot.stateAsString();
        int volumeSize = snapshot.volumeSize() != null ? snapshot.volumeSize() : 0;
        String name = snapshot.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(snapshotId);
        boolean isOld = snapshot.startTime() != null && Duration.between(snapshot.startTime(), Instant.now()).toDays() > 90L;
        boolean orphaned = false;
        if (snapshot.volumeId() != null && !snapshot.volumeId().isEmpty()) {
            try {
                ec2.describeVolumes(DescribeVolumesRequest.builder().volumeIds(snapshot.volumeId()).build());
            } catch (Ec2Exception e) {
                if ("InvalidVolume.NotFound".equals(e.awsErrorDetails().errorCode())) {
                    orphaned = true;
                }
            }
        }

        String recommendation = engine.getEbsSnapshotRecommendation(orphaned, isOld);

        double cost = pricingService.getEbsSnapshotPrice(volumeSize, region);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("EBS Snapshot");
        dto.setResourceId(snapshotId);
        dto.setResourceName(name);
        dto.setInstanceType(volumeSize + "GB / " + state);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (snapshot.startTime() != null) {
            dto.setCreatedDate(snapshot.startTime().toString());
        }
        return dto;
    }
}
