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
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.HostedZone;

/**
 * Scans Route 53 hosted zones for empty or low-record-count zones.
 *
 * <p>Checks record count and zone visibility (public/private). Delegates to
 * {@link RecommendationEngine#getRoute53Recommendation} for classification.
 * Runs as a global scanner.</p>
 */
@Component
public class Route53Scanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(Route53Scanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public Route53Scanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public boolean isGlobal() {
        return true;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans Route 53 hosted zones globally.
     *
     * <p>Calls {@code listHostedZones} and {@code getHostedZone} per zone to get record count.
     * Errors on individual zones are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered hosted zones with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var route53 = ReadOnlyAwsClientFactory.build(Route53Client.builder(), creds, Region.of(region))) {
            for (HostedZone zone : route53.listHostedZones().hostedZones()) {
                try {
                    results.add(this.buildDto(zone, route53, region));
                } catch (Exception e) {
                    log.warn("Failed to process Route 53 hosted zone {}: {}", zone.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Route 53 scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Route 53 hosted zone.
     *
     * <p>Fetches record count and determines public/private visibility. Cost is the
     * fixed per-hosted-zone monthly rate.</p>
     */
    private ResourceDto buildDto(HostedZone zone, Route53Client route53, String region) {
        long recordCount = route53.getHostedZone((r) -> r.id(zone.id())).hostedZone().resourceRecordSetCount();
        String visibility = zone.config().privateZone() ? "private" : "public";
        String description = recordCount + " records / " + visibility;
        String recommendation = engine.getRoute53Recommendation(recordCount);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Route 53");
        dto.setResourceId(zone.id());
        dto.setResourceName(zone.name());
        dto.setInstanceType(description);
        dto.setState("active");
        dto.setMonthlyCostUsd(this.pricingService.getRoute53HostedZonePrice(region));
        dto.setRecommendation(recommendation);
        dto.setCreatedDate((String) null);
        return dto;
    }
}
