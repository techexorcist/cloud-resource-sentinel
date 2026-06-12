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
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.Cluster;
import software.amazon.awssdk.services.eks.model.DescribeClusterRequest;
import software.amazon.awssdk.services.eks.model.ListNodegroupsRequest;

/**
 * Scans EKS clusters for idle clusters with no node groups.
 *
 * <p>Checks cluster status and node group count. Clusters with zero node groups
 * still incur the EKS control plane cost. Delegates to
 * {@link RecommendationEngine#getEksRecommendation} for classification.</p>
 */
@Component
public class EksScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EksScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public EksScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans EKS clusters in the given region.
     *
     * <p>Calls {@code listClustersPaginator}, then {@code describeCluster} and
     * {@code listNodegroups} per cluster. Errors on individual clusters are logged
     * and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered EKS clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var eks = ReadOnlyAwsClientFactory.build(EksClient.builder(), creds, Region.of(region))) {

            List<String> clusterNames = new ArrayList<>();
            eks.listClustersPaginator().clusters().forEach(clusterNames::add);

            for (String clusterName : clusterNames) {
                try {
                    Cluster cluster = eks.describeCluster(DescribeClusterRequest.builder().name(clusterName).build()).cluster();
                    List<String> nodeGroups = eks.listNodegroups(ListNodegroupsRequest.builder().clusterName(clusterName).build()).nodegroups();
                    String status = cluster.statusAsString();
                    String recommendation = engine.getEksRecommendation(status, nodeGroups.size());
                    double cost = pricingService.getEksClusterPrice(region);
                    String version = cluster.version() != null ? cluster.version() : "unknown";
                    String description = String.format("k8s %s / Node groups: %d", version, nodeGroups.size());
                    var dto = new ResourceDto();
                    dto.setRegion(region);
                    dto.setResourceType("EKS");
                    dto.setResourceId(cluster.arn());
                    dto.setResourceName(clusterName);
                    dto.setInstanceType(description);
                    dto.setState(status);
                    dto.setMonthlyCostUsd(cost);
                    dto.setRecommendation(recommendation);
                    if (cluster.createdAt() != null) {
                        dto.setCreatedDate(cluster.createdAt().toString());
                    }
                    results.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to process EKS cluster {}: {}", clusterName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("EKS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }
}
