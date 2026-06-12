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
import software.amazon.awssdk.services.grafana.GrafanaClient;
import software.amazon.awssdk.services.grafana.model.WorkspaceSummary;
import software.amazon.awssdk.services.grafana.model.WorkspaceStatus;

/**
 * Scans Amazon Managed Grafana workspaces for non-active or idle workspaces.
 *
 * <p>Checks workspace status. Delegates to {@link RecommendationEngine#getGrafanaRecommendation}
 * for classification. Runs as a global scanner.</p>
 */
@Component
public class GrafanaScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(GrafanaScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public GrafanaScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Managed Grafana workspaces globally.
     *
     * <p>Calls {@code listWorkspaces} and iterates over all workspace summaries.
     * Errors on individual workspaces are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Grafana workspaces with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (GrafanaClient grafana = ReadOnlyAwsClientFactory.build(GrafanaClient.builder(), creds, Region.of(region))) {
            grafana.listWorkspaces(r -> {}).workspaces().forEach(workspace -> {
                try {
                    results.add(buildDto(workspace, region));
                } catch (Exception e) {
                    log.warn("Failed to process Managed Grafana workspace {}: {}", workspace.id(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Managed Grafana scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Managed Grafana workspace.
     *
     * <p>Populates workspace status. Cost is the fixed per-workspace monthly rate.</p>
     */
    private ResourceDto buildDto(WorkspaceSummary workspace, String region) {
        String workspaceId = workspace.id();
        String workspaceName = workspace.name();
        WorkspaceStatus status = workspace.status();
        String statusStr = status != null ? status.toString() : "UNKNOWN";
        String instanceType = "Managed Grafana";

        String recommendation = engine.getGrafanaRecommendation(statusStr);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Managed Grafana");
        dto.setResourceId(workspaceId);
        dto.setResourceName(workspaceName);
        dto.setInstanceType(instanceType);
        dto.setState(statusStr.toLowerCase());
        dto.setMonthlyCostUsd(this.pricingService.getGrafanaWorkspacePrice(region));
        dto.setRecommendation(recommendation);

        return dto;
    }
}
