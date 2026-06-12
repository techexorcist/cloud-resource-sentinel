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
import software.amazon.awssdk.services.lightsail.LightsailClient;
import software.amazon.awssdk.services.lightsail.model.Instance;
import software.amazon.awssdk.services.lightsail.model.RelationalDatabase;

/**
 * Scans Lightsail instances and relational databases for stopped or idle resources.
 *
 * <p>Checks instance and database state. Uses bundle-based pricing lookup (nano
 * through 2xlarge). Delegates to {@link RecommendationEngine#getLightsailRecommendation}
 * for classification.</p>
 */
@Component
public class LightsailScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(LightsailScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public LightsailScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Lightsail instances and relational databases in the given region.
     *
     * <p>Calls {@code getInstances} and {@code getRelationalDatabases}. Errors on
     * individual instances or databases are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Lightsail resources with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (LightsailClient lightsail = ReadOnlyAwsClientFactory.build(LightsailClient.builder(), creds, Region.of(region))) {
            // Scan instances
            try {
                lightsail.getInstances().instances().forEach(instance -> {
                    try {
                        results.add(buildInstanceDto(instance, region));
                    } catch (Exception e) {
                        log.warn("Failed to process Lightsail instance {}: {}", instance.name(), e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Lightsail instances scan failed in region {}: {}", region, e.getMessage());
            }

            // Scan relational databases
            try {
                lightsail.getRelationalDatabases().relationalDatabases().forEach(db -> {
                    try {
                        results.add(buildDatabaseDto(db, region));
                    } catch (Exception e) {
                        log.warn("Failed to process Lightsail DB {}: {}", db.name(), e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("Lightsail databases scan failed in region {}: {}", region, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Lightsail scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Lightsail instance.
     *
     * <p>Uses bundle-based pricing lookup (nano through 2xlarge). Populates bundle ID
     * and blueprint name.</p>
     */
    private ResourceDto buildInstanceDto(Instance instance, String region) {
        String name = instance.name();
        String state = instance.state() != null ? instance.state().name() : "unknown";
        String bundleId = instance.bundleId();
        String instanceType = bundleId + " / " + instance.blueprintName();

        String recommendation = engine.getLightsailRecommendation(state);

        double cost = getLightsailBundlePrice(bundleId);
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Lightsail");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (instance.createdAt() != null) {
            dto.setCreatedDate(instance.createdAt().toString());
        }

        return dto;
    }

    /**
     * Builds a ResourceDto for a single Lightsail relational database.
     *
     * <p>Uses bundle-based pricing lookup. Populates database bundle ID and engine type.</p>
     */
    private ResourceDto buildDatabaseDto(RelationalDatabase db, String region) {
        String name = db.name();
        String state = db.state();
        String instanceType = db.relationalDatabaseBundleId() + " / " + db.engine();

        String recommendation = engine.getLightsailRecommendation(state);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Lightsail DB");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        dto.setMonthlyCostUsd(getLightsailBundlePrice(db.relationalDatabaseBundleId()));
        dto.setRecommendation(recommendation);
        if (db.createdAt() != null) {
            dto.setCreatedDate(db.createdAt().toString());
        }

        return dto;
    }

    private static double getLightsailBundlePrice(String bundleId) {
        if (bundleId == null) return 0.0;
        String lower = bundleId.toLowerCase();
        if (lower.contains("nano")) return 3.50;
        if (lower.contains("micro")) return 5.0;
        if (lower.contains("small")) return 10.0;
        if (lower.contains("medium")) return 20.0;
        if (lower.contains("2xlarge")) return 160.0;
        if (lower.contains("xlarge")) return 80.0;
        if (lower.contains("large")) return 40.0;
        return 5.0; // default to cheapest
    }
}
