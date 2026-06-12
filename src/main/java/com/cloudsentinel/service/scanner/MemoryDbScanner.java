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
import software.amazon.awssdk.services.memorydb.MemoryDbClient;
import software.amazon.awssdk.services.memorydb.model.Cluster;

/**
 * Scans MemoryDB for Redis clusters for idle or underutilized clusters.
 *
 * <p>Checks cluster status and shard count. Estimates cost using ElastiCache pricing
 * with 2 nodes per shard (primary + replica). Delegates to
 * {@link RecommendationEngine#getMemoryDbRecommendation} for classification.</p>
 */
@Component
public class MemoryDbScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(MemoryDbScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public MemoryDbScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans MemoryDB for Redis clusters in the given region.
     *
     * <p>Calls {@code describeClusters} and iterates over all clusters.
     * Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered MemoryDB clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (MemoryDbClient memoryDb = ReadOnlyAwsClientFactory.build(MemoryDbClient.builder(), creds, Region.of(region))) {
            memoryDb.describeClusters(r -> {}).clusters().forEach(cluster -> {
                try {
                    results.add(buildDto(cluster, region));
                } catch (Exception e) {
                    log.warn("Failed to process MemoryDB cluster {}: {}", cluster.name(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("MemoryDB scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single MemoryDB cluster.
     *
     * <p>Estimates cost using ElastiCache pricing with 2 nodes per shard (primary + replica).
     * Populates node type and shard count.</p>
     */
    private ResourceDto buildDto(Cluster cluster, String region) {
        String clusterName = cluster.name();
        String status = cluster.status();
        int shardCount = cluster.numberOfShards() != null ? cluster.numberOfShards() : 0;
        String nodeType = cluster.nodeType();
        String instanceType = nodeType + " / " + shardCount + " shards";

        String recommendation = engine.getMemoryDbRecommendation(status, shardCount);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("MemoryDB");
        dto.setResourceId(clusterName);
        dto.setResourceName(clusterName);
        dto.setInstanceType(instanceType);
        dto.setState(status);
        // MemoryDB pricing is similar to ElastiCache; estimate per shard with 2 nodes each (primary + replica)
        double cost = (nodeType != null && shardCount > 0)
                ? pricingService.getElastiCachePrice(nodeType, region) * shardCount * 2
                : 0.0;
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);

        return dto;
    }
}
