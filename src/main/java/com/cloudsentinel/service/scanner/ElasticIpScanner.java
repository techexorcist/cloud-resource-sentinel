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
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.Tag;

/**
 * Scans Elastic IP addresses for unattached IPs incurring public IPv4 charges.
 *
 * <p>Checks whether each EIP is associated with an instance. All public IPv4 addresses
 * are charged at $3.65/mo since the Feb 2024 AWS pricing change. Delegates to
 * {@link RecommendationEngine#getElasticIpRecommendation} for classification.</p>
 */
@Component
public class ElasticIpScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(ElasticIpScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ElasticIpScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Elastic IP addresses in the given region.
     *
     * <p>Calls {@code describeAddresses} and iterates over all EIPs.
     * Errors on individual addresses are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Elastic IPs with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region))) {

            for (Address address : ec2.describeAddresses().addresses()) {
                try {
                    results.add(buildDto(address, region));
                } catch (Exception e) {
                    log.warn("Failed to process Elastic IP {}: {}", address.publicIp(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Elastic IP scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Elastic IP address.
     *
     * <p>Checks association status. All public IPv4 addresses cost $3.65/mo since the
     * Feb 2024 AWS pricing change, regardless of association state.</p>
     */
    private ResourceDto buildDto(Address address, String region) {
        String publicIp = address.publicIp();
        boolean associated = address.associationId() != null && !address.associationId().isEmpty();
        String state = associated ? "associated" : "unattached";
        String name = address.tags().stream().filter(t -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(publicIp);
        double cost = pricingService.getElasticIpPrice(region);
        String recommendation = engine.getElasticIpRecommendation(associated);
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Elastic IP");
        dto.setResourceId(address.allocationId());
        dto.setResourceName(name);
        String instanceId = address.instanceId();
        dto.setInstanceType(instanceId != null && !instanceId.isBlank() ? "Elastic IP / " + instanceId : "Elastic IP");
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }
}
