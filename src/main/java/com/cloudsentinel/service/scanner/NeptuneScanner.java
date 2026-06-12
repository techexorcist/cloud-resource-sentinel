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
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.DBCluster;

/**
 * Scans Amazon Neptune graph database clusters for idle or stopped clusters.
 *
 * <p>Checks cluster status and member count. Filters to neptune-engine clusters only.
 * Estimates cost using RDS-like pricing per member. Delegates to
 * {@link RecommendationEngine#getNeptuneRecommendation} for classification.</p>
 */
@Component
public class NeptuneScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(NeptuneScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public NeptuneScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Neptune graph database clusters in the given region.
     *
     * <p>Calls {@code describeDBClustersPaginator} and filters to neptune-engine clusters.
     * Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Neptune clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (NeptuneClient neptune = ReadOnlyAwsClientFactory.build(NeptuneClient.builder(), creds, Region.of(region))) {
            neptune.describeDBClustersPaginator().dbClusters().forEach(cluster -> {
                try {
                    String engineName = cluster.engine();
                    if (engineName != null && engineName.equals("neptune")) {
                        results.add(buildDto(cluster, region));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process Neptune cluster {}: {}", cluster.dbClusterIdentifier(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Neptune scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Neptune cluster.
     *
     * <p>Estimates cost using RDS-like pricing with db.r5.large baseline per member.
     * Stopped clusters report $0 cost.</p>
     */
    private ResourceDto buildDto(DBCluster cluster, String region) {
        String clusterId = cluster.dbClusterIdentifier();
        String status = cluster.status();
        int memberCount = cluster.dbClusterMembers() != null ? cluster.dbClusterMembers().size() : 0;
        String instanceType = cluster.engineVersion() + " / " + memberCount + " instances";

        String recommendation = engine.getNeptuneRecommendation(status, memberCount);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Neptune");
        dto.setResourceId(clusterId);
        dto.setResourceName(clusterId);
        dto.setInstanceType(instanceType);
        dto.setState(status);
        // Estimate: Neptune uses similar pricing to RDS; assume db.r5.large per member as baseline
        double cost = "stopped".equalsIgnoreCase(status) ? 0.0
                : pricingService.getRdsPrice("db.r5.large", "neptune", region) * memberCount;
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (cluster.clusterCreateTime() != null) {
            dto.setCreatedDate(cluster.clusterCreateTime().toString());
        }

        return dto;
    }
}
