package com.cloudsentinel.service;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;

/**
 * Provides the list of available AWS regions, either by querying the EC2 API or falling back
 * to a hardcoded list of 30 regions.
 *
 * <p>The live query uses the default credentials provider (from the host environment) and
 * targets {@code us-east-1}. If the query fails (e.g., no credentials available, network
 * issues), the fallback list is returned. The fallback list covers all commercially available
 * AWS regions as of the last update.</p>
 *
 * <p>All AWS clients are built through {@link com.cloudsentinel.config.ReadOnlyAwsClientFactory}
 * to enforce the read-only security guardrail.</p>
 */
@Service
public class AwsRegionService {

    private static final Logger log = LoggerFactory.getLogger(AwsRegionService.class);

    /** Hardcoded fallback region list covering 30 commercially available AWS regions. */
    private static final List<String> FALLBACK_REGIONS = List.of(
            "af-south-1",
            "ap-east-1", "ap-northeast-1", "ap-northeast-2", "ap-northeast-3",
            "ap-south-1", "ap-south-2",
            "ap-southeast-1", "ap-southeast-2", "ap-southeast-3", "ap-southeast-4", "ap-southeast-5",
            "ca-central-1", "ca-west-1",
            "eu-central-1", "eu-central-2",
            "eu-north-1",
            "eu-south-1", "eu-south-2",
            "eu-west-1", "eu-west-2", "eu-west-3",
            "il-central-1",
            "me-central-1", "me-south-1",
            "sa-east-1",
            "us-east-1", "us-east-2",
            "us-west-1", "us-west-2"
    );

    /**
     * Returns a sorted list of available AWS region names.
     *
     * <p>Attempts to discover regions dynamically via the EC2 {@code DescribeRegions} API.
     * If the API call fails for any reason, returns the hardcoded {@link #FALLBACK_REGIONS}
     * list to ensure the application remains functional without AWS connectivity.</p>
     *
     * @return a sorted list of AWS region name strings (e.g., "us-east-1", "eu-west-1")
     */
    public List<String> listRegions() {
        try (Ec2Client ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), Region.US_EAST_1)) {
            DescribeRegionsResponse response = ec2.describeRegions();
            return response.regions().stream()
                    .map(r -> r.regionName())
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to fetch AWS regions, using fallback list", e);
            return FALLBACK_REGIONS;
        }
    }
}
