package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.Cluster;
import software.amazon.awssdk.services.ecs.model.DescribeClustersRequest;

/**
 * Scans ECS clusters for empty or idle clusters with no running tasks.
 *
 * <p>Checks running task count, active service count, and registered container instances.
 * Delegates to {@link RecommendationEngine#getEcsRecommendation} for classification.</p>
 */
@Component
public class EcsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EcsScanner.class);
    private final RecommendationEngine engine;

    public EcsScanner(RecommendationEngine engine) {
        this.engine = engine;
    }

    /**
     * Scans ECS clusters in the given region.
     *
     * <p>Calls {@code listClustersPaginator} then {@code describeClusters} in batch.
     * Checks running tasks, active services, and container instance counts.
     * Errors on individual clusters are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered ECS clusters with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ecs = ReadOnlyAwsClientFactory.build(EcsClient.builder(), creds, Region.of(region))) {
            List<String> clusterArns = new ArrayList<>();
            ecs.listClustersPaginator().clusterArns().forEach(clusterArns::add);

            if (clusterArns.isEmpty()) return results;

            for (Cluster cluster : ecs.describeClusters(DescribeClustersRequest.builder().clusters(clusterArns).build()).clusters()) {
                try {
                    int runningTasks = cluster.runningTasksCount();
                    int serviceCount = cluster.activeServicesCount();
                    int containerInstances = cluster.registeredContainerInstancesCount();
                    String recommendation = engine.getEcsRecommendation(runningTasks, serviceCount);
                    String description = "Services: %d / Tasks: %d / Instances: %d".formatted(serviceCount, runningTasks, containerInstances);

                    var dto = new ResourceDto();
                    dto.setRegion(region);
                    dto.setResourceType("ECS");
                    dto.setResourceId(cluster.clusterArn());
                    dto.setResourceName(cluster.clusterName());
                    dto.setInstanceType(description);
                    dto.setState(cluster.status());
                    dto.setMonthlyCostUsd(0.0);
                    dto.setRecommendation(recommendation);
                    results.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to process ECS cluster {}: {}", cluster.clusterName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("ECS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }
}
