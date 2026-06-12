package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.FindingType;
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
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ListStackResourcesRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;

/**
 * Scans AWS CloudFormation stacks for failed or drifted stacks.
 *
 * <p>Checks stack status and resource count. Skips stacks in DELETE_COMPLETE or
 * DELETE_IN_PROGRESS state. Delegates to {@link RecommendationEngine#getCloudFormationRecommendation}
 * for classification.</p>
 */
@Component
public class CloudFormationScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(CloudFormationScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public CloudFormationScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
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
     * Scans CloudFormation stacks in the given region.
     *
     * <p>Calls {@code describeStacks} and {@code listStackResources} per stack.
     * Skips stacks in DELETE_COMPLETE or DELETE_IN_PROGRESS state.
     * Errors on individual stacks are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered stacks with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var cfn = ReadOnlyAwsClientFactory.build(CloudFormationClient.builder(), creds, Region.of(region))) {
            for (Stack stack : cfn.describeStacks().stacks()) {
                try {
                    ResourceDto dto = buildDto(cfn, stack, region);
                    if (dto != null) {
                        results.add(dto);
                    }
                } catch (Exception e) {
                    log.warn("Failed to process CloudFormation stack {}: {}", stack.stackName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("CloudFormation scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single CloudFormation stack.
     *
     * <p>Counts stack resources and checks stack status. Returns {@code null} for
     * deleted stacks. Cost is $0 (CloudFormation itself is free).</p>
     */
    private ResourceDto buildDto(CloudFormationClient cfn, Stack stack, String region) {
        String status = stack.stackStatusAsString();

        if ("DELETE_COMPLETE".equals(status) || "DELETE_IN_PROGRESS".equals(status)) {
            return null;
        }

        int resourceCount = 0;
        try {
            resourceCount = cfn.listStackResources(ListStackResourcesRequest.builder()
                    .stackName(stack.stackName()).build())
                    .stackResourceSummaries().size();
        } catch (Exception e) {
            log.debug("Cannot count resources for stack {}: {}", stack.stackName(), e.getMessage());
        }

        String recommendation = engine.getCloudFormationRecommendation(status);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("CloudFormation");
        dto.setResourceId(stack.stackId());
        dto.setResourceName(stack.stackName());
        dto.setInstanceType(status + " / " + resourceCount + " resources");
        dto.setState(status);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (stack.creationTime() != null) {
            dto.setCreatedDate(stack.creationTime().toString());
        }

        return dto;
    }
}
