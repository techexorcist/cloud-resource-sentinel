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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.CacheCluster;
import software.amazon.awssdk.services.elasticache.model.DescribeCacheClustersRequest;

/**
 * Scans ElastiCache clusters for underutilized or idle cache nodes.
 *
 * <p>Checks 7-day average CPU utilization via CloudWatch. Multiplies per-node price
 * by numCacheNodes for multi-node clusters. Delegates to
 * {@link RecommendationEngine#getRecommendation} for classification.</p>
 */
@Component
public class ElastiCacheScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ElastiCacheScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ElastiCacheScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans ElastiCache clusters in the given region.
     *
     * <p>Calls {@code describeCacheClustersPaginator} with node info enabled, then queries
     * CloudWatch CPUUtilization per cluster. Errors on individual clusters are logged
     * and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered ElastiCache clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var cache = ReadOnlyAwsClientFactory.build(ElastiCacheClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            DescribeCacheClustersRequest request = DescribeCacheClustersRequest.builder().showCacheNodeInfo(true).build();
            cache.describeCacheClustersPaginator(request).cacheClusters().forEach(cluster -> {
                try {
                    results.add(buildDto(cluster, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process ElastiCache cluster {}: {}", cluster.cacheClusterId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("ElastiCache scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single ElastiCache cluster.
     *
     * <p>Fetches 7-day average CPU utilization. Multiplies per-node price by
     * {@code numCacheNodes} for multi-node clusters.</p>
     */
    private ResourceDto buildDto(CacheCluster cluster, CloudWatchClient cw, String region) {
        String clusterId = cluster.cacheClusterId();
        String nodeType = cluster.cacheNodeType();
        String state = cluster.cacheClusterStatus();
        String engineName = cluster.engine();
        double cpu = Ec2Scanner.getCpuUtilization(cw, "AWS/ElastiCache", "CacheClusterId", clusterId);
        int numNodes = cluster.numCacheNodes() != null ? cluster.numCacheNodes() : 1;
        double cost = pricingService.getElastiCachePrice(nodeType, region) * numNodes;
        String recommendation = engine.getRecommendation(cpu, state);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("ElastiCache");
        dto.setResourceId(clusterId);
        dto.setResourceName(clusterId);
        dto.setInstanceType(nodeType);
        dto.setState(state);
        dto.setCpuUtilizationAvg(cpu);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (cluster.cacheClusterCreateTime() != null) {
            dto.setCreatedDate(cluster.cacheClusterCreateTime().toString());
        }
        return dto;
    }
}
