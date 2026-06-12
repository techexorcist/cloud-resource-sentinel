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
import software.amazon.awssdk.services.docdb.DocDbClient;
import software.amazon.awssdk.services.docdb.model.DBCluster;

/**
 * Scans Amazon DocumentDB clusters for idle or stopped clusters.
 *
 * <p>Checks cluster status and member count. Filters to docdb-engine clusters only.
 * Estimates cost using RDS-like pricing per member. Delegates to
 * {@link RecommendationEngine#getDocumentDbRecommendation} for classification.</p>
 */
@Component
public class DocumentDbScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(DocumentDbScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public DocumentDbScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans DocumentDB clusters in the given region.
     *
     * <p>Calls {@code describeDBClustersPaginator} and filters to docdb-engine clusters.
     * Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered DocumentDB clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (DocDbClient docDb = ReadOnlyAwsClientFactory.build(DocDbClient.builder(), creds, Region.of(region))) {
            docDb.describeDBClustersPaginator().dbClusters().forEach(cluster -> {
                try {
                    String engineName = cluster.engine();
                    if (engineName != null && engineName.equals("docdb")) {
                        results.add(buildDto(cluster, region));
                    }
                } catch (Exception e) {
                    log.warn("Failed to process DocumentDB cluster {}: {}", cluster.dbClusterIdentifier(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("DocumentDB scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single DocumentDB cluster.
     *
     * <p>Estimates cost using RDS-like pricing with db.r5.large baseline per member.
     * Stopped clusters report $0 cost.</p>
     */
    private ResourceDto buildDto(DBCluster cluster, String region) {
        String clusterId = cluster.dbClusterIdentifier();
        String status = cluster.status();
        int memberCount = cluster.dbClusterMembers() != null ? cluster.dbClusterMembers().size() : 0;
        String instanceType = cluster.engineVersion() + " / " + memberCount + " instances";

        String recommendation = engine.getDocumentDbRecommendation(status, memberCount);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("DocumentDB");
        dto.setResourceId(clusterId);
        dto.setResourceName(clusterId);
        dto.setInstanceType(instanceType);
        dto.setState(status);
        // Estimate: DocumentDB uses RDS-like pricing; assume db.r5.large per member as baseline
        double cost = "stopped".equalsIgnoreCase(status) ? 0.0
                : pricingService.getRdsPrice("db.r5.large", "docdb", region) * memberCount;
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (cluster.clusterCreateTime() != null) {
            dto.setCreatedDate(cluster.clusterCreateTime().toString());
        }

        return dto;
    }
}
