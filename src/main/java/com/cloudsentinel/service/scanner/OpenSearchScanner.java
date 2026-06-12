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
import software.amazon.awssdk.services.opensearch.OpenSearchClient;
import software.amazon.awssdk.services.opensearch.model.DomainStatus;

/**
 * Scans OpenSearch Service domains for underutilized or idle clusters.
 *
 * <p>Checks 7-day average CPU utilization via CloudWatch. Prices based on instance
 * type and node count. Delegates to {@link RecommendationEngine#getRecommendation}
 * for classification.</p>
 */
@Component
public class OpenSearchScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(OpenSearchScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public OpenSearchScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Scans OpenSearch Service domains in the given region.
     *
     * <p>Calls {@code listDomainNames} and {@code describeDomain} per domain, then queries
     * CloudWatch CPUUtilization. Errors on individual domains are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered OpenSearch domains with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var openSearch = ReadOnlyAwsClientFactory.build(OpenSearchClient.builder(), creds, Region.of(region));
             var cw = ReadOnlyAwsClientFactory.build(CloudWatchClient.builder(), creds, Region.of(region))) {

            openSearch.listDomainNames(r -> {}).domainNames().forEach(domainInfo -> {
                try {
                    String domainName = domainInfo.domainName();
                    DomainStatus domain = openSearch.describeDomain(r -> r.domainName(domainName)).domainStatus();
                    results.add(buildDto(domain, cw, region));
                } catch (Exception e) {
                    log.warn("Failed to process OpenSearch domain {}: {}", domainInfo.domainName(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("OpenSearch scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single OpenSearch domain.
     *
     * <p>Fetches 7-day average CPU utilization. Cost is based on instance type and node count.
     * Strips {@code .search} suffix from instance type before pricing lookup.</p>
     */
    private ResourceDto buildDto(DomainStatus domain, CloudWatchClient cw, String region) {
        String domainName = domain.domainName();
        String engineVersion = domain.engineVersion();
        String instanceType = domain.clusterConfig().instanceTypeAsString();
        int instanceCount = domain.clusterConfig().instanceCount();
        String description = engineVersion + " / " + instanceType + " x " + instanceCount + " nodes";
        String state = domain.processing() ? "processing" : "active";
        double cpu = Ec2Scanner.getCpuUtilization(cw, "AWS/ES", "DomainName", domainName);
        String recommendation = engine.getRecommendation(cpu, state);

        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("OpenSearch");
        dto.setResourceId(domain.arn());
        dto.setResourceName(domainName);
        dto.setInstanceType(description);
        dto.setState(state);
        dto.setCpuUtilizationAvg(cpu);
        double cost = pricingService.getOpenSearchPrice(instanceType, instanceCount, region);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }
}
