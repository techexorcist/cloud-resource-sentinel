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
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBSnapshot;

/**
 * Scans manual RDS snapshots for aged snapshots that may no longer be needed.
 *
 * <p>Checks snapshot age (flags over 90 days). Only scans manual snapshots, not
 * automated ones. Delegates to {@link RecommendationEngine#getRdsSnapshotRecommendation}
 * for classification.</p>
 */
@Component
public class RdsSnapshotScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(RdsSnapshotScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public RdsSnapshotScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans manual RDS snapshots in the given region.
     *
     * <p>Calls {@code describeDBSnapshotsPaginator} filtered to "manual" snapshot type.
     * Errors on individual snapshots are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered RDS snapshots with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var rds = ReadOnlyAwsClientFactory.build(RdsClient.builder(), creds, Region.of(region))) {

            rds.describeDBSnapshotsPaginator(r -> r.snapshotType("manual")).dbSnapshots().stream().forEach(snapshot -> {
                try {
                    results.add(buildDto(snapshot, region));
                } catch (Exception e) {
                    log.warn("Failed to process RDS Snapshot {}: {}", snapshot.dbSnapshotIdentifier(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("RDS Snapshot scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single RDS snapshot.
     *
     * <p>Checks snapshot age (flags over 90 days). Cost is based on allocated storage size.</p>
     */
    private ResourceDto buildDto(DBSnapshot snapshot, String region) {
        String snapshotId = snapshot.dbSnapshotIdentifier();
        String status = snapshot.status();
        String engine = snapshot.engine() != null ? snapshot.engine() : "unknown";
        int allocatedStorage = snapshot.allocatedStorage() != null ? snapshot.allocatedStorage() : 0;
        boolean isOld = snapshot.snapshotCreateTime() != null && Duration.between(snapshot.snapshotCreateTime(), Instant.now()).toDays() > 90L;
        String recommendation = this.engine.getRdsSnapshotRecommendation(isOld);
        double cost = pricingService.getRdsSnapshotPrice(allocatedStorage, region);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("RDS Snapshot");
        dto.setResourceId(snapshot.dbSnapshotArn());
        dto.setResourceName(snapshotId);
        dto.setInstanceType(engine + " / " + allocatedStorage + "GB");
        dto.setState(status);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (snapshot.snapshotCreateTime() != null) {
            dto.setCreatedDate(snapshot.snapshotCreateTime().toString());
        }

        return dto;
    }
}
