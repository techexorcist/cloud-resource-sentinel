package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Vpc;

/**
 * Scans VPCs for empty or unused virtual private clouds.
 *
 * <p>Checks subnet count and running instance count per VPC. Identifies default VPCs.
 * Delegates to {@link RecommendationEngine#getVpcRecommendation} for classification.</p>
 */
@Component
public class VpcScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(VpcScanner.class);
    private final RecommendationEngine engine;

    public VpcScanner(RecommendationEngine engine) {
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.GOVERNANCE;
    }

    /**
     * Scans VPCs in the given region.
     *
     * <p>Calls {@code describeVpcs}, {@code describeSubnets}, and {@code describeInstances}
     * per VPC to count subnets and running instances. Builds DTOs inline.
     * Errors on individual VPCs are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered VPCs with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, Region.of(region))) {
            for (Vpc vpc : ec2.describeVpcs().vpcs()) {
                try {
                    int subnetCount = ec2.describeSubnets((DescribeSubnetsRequest) DescribeSubnetsRequest.builder().filters(new Filter[]{(Filter) Filter.builder().name("vpc-id").values(new String[]{vpc.vpcId()}).build()}).build()).subnets().size();
                    int instanceCount = 0;

                    for (Reservation r : ec2.describeInstances((DescribeInstancesRequest) DescribeInstancesRequest.builder().filters(new Filter[]{(Filter) Filter.builder().name("vpc-id").values(new String[]{vpc.vpcId()}).build(), (Filter) Filter.builder().name("instance-state-name").values(new String[]{"running"}).build()}).build()).reservations()) {
                        instanceCount += r.instances().size();
                    }

                    String recommendation = this.engine.getVpcRecommendation(instanceCount);
                    boolean isDefault = Boolean.TRUE.equals(vpc.isDefault());
                    String name = vpc.tags().stream().filter((t) -> "Name".equals(t.key())).map(Tag::value).findFirst().orElse(vpc.vpcId());
                    String description = String.format("%s / Subnets: %d / Instances: %d%s", vpc.cidrBlock(), subnetCount, instanceCount, isDefault ? " (default)" : "");
                    ResourceDto dto = new ResourceDto();
                    dto.setRegion(region);
                    dto.setResourceType("VPC");
                    dto.setResourceId(vpc.vpcId());
                    dto.setResourceName(name);
                    dto.setInstanceType(description);
                    dto.setState(vpc.stateAsString());
                    dto.setMonthlyCostUsd(0.0);
                    dto.setRecommendation(recommendation);
                    results.add(dto);
                } catch (Exception e) {
                    log.warn("Failed to process VPC {}: {}", vpc.vpcId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("VPC scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }
}
