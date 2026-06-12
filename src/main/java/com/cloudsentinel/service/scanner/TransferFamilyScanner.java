package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.transfer.TransferClient;
import software.amazon.awssdk.services.transfer.model.DescribedServer;
import software.amazon.awssdk.services.transfer.model.ListedServer;

/**
 * Scans AWS Transfer Family servers for offline or idle SFTP/FTPS/FTP endpoints.
 *
 * <p>Checks server state, endpoint type, and enabled protocols. Only prices servers
 * in ONLINE state. Delegates to {@link RecommendationEngine#getTransferFamilyRecommendation}
 * for classification.</p>
 */
@Component
public class TransferFamilyScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(TransferFamilyScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public TransferFamilyScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Transfer Family servers in the given region.
     *
     * <p>Calls {@code listServers} and {@code describeServer} per server.
     * Errors on individual servers are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Transfer Family servers with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var transfer = ReadOnlyAwsClientFactory.build(TransferClient.builder(), creds, Region.of(region))) {
            for (ListedServer server : transfer.listServers().servers()) {
                try {
                    DescribedServer described = transfer.describeServer((r) -> r.serverId(server.serverId())).server();
                    results.add(this.buildDto(described, region));
                } catch (Exception e) {
                    log.warn("Failed to process Transfer Family server {}: {}", server.serverId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Transfer Family scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Transfer Family server.
     *
     * <p>Populates endpoint type, protocols, and state. Only ONLINE servers incur cost;
     * offline servers report $0.</p>
     */
    private ResourceDto buildDto(DescribedServer server, String region) {
        String serverId = server.serverId();
        String state = server.stateAsString();
        String endpointType = server.endpointTypeAsString();
        String protocols = server.protocols().stream().map((p) -> p.toString()).collect(Collectors.joining(","));
        String description = protocols + " / " + endpointType + " / " + state;
        String recommendation = engine.getTransferFamilyRecommendation(state);

        double cost = "ONLINE".equals(state) ? this.pricingService.getTransferFamilyPrice(region) : 0.0;
        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Transfer Family");
        dto.setResourceId(serverId);
        dto.setResourceName(serverId);
        dto.setInstanceType(description);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        return dto;
    }
}
