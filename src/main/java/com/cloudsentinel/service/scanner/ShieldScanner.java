package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.shield.ShieldClient;
import software.amazon.awssdk.services.shield.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans AWS Shield Advanced subscriptions and protected resources.
 *
 * <p>Checks subscription status and lists protected resources. Shield Advanced costs
 * $3,000/mo fixed. Skips Shield Standard (free, always enabled). Handles recommendations
 * inline due to unique subscription-level cost semantics. Runs as a global scanner
 * (us-east-1).</p>
 */
@Component
public class ShieldScanner implements ResourceScanner {

    private static final Logger log = LoggerFactory.getLogger(ShieldScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public ShieldScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public boolean isGlobal() {
        return true;
    }

    @Override
    public ScanCategory category() {
        return ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans Shield Advanced subscription and protected resources globally (us-east-1).
     *
     * <p>Calls {@code describeSubscription} to check if Shield Advanced is active, then
     * {@code listProtections} for protected resources. Returns empty list if Shield
     * Advanced is not enabled. Errors are logged and the scan returns gracefully.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan (ignored; uses us-east-1)
     * @return list of Shield subscription and protected resources with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (ShieldClient shield = ReadOnlyAwsClientFactory.build(
                ShieldClient.builder(), creds, Region.US_EAST_1)) {

            // Check if Shield Advanced is active
            DescribeSubscriptionResponse subscription;
            try {
                subscription = shield.describeSubscription();
            } catch (ResourceNotFoundException e) {
                // Shield Advanced not enabled — nothing to report
                return results;
            }

            if (subscription.subscription() == null) return results;

            Subscription sub = subscription.subscription();
            String state = "ACTIVE"; // If describeSubscription succeeds, it's active
            String autoRenew = sub.autoRenewAsString() != null ? sub.autoRenewAsString() : "UNKNOWN";

            // Shield Advanced subscription itself
            ResourceDto subDto = new ResourceDto();
            subDto.setRegion("global");
            subDto.setResourceType("Shield Advanced");
            subDto.setResourceId("shield-advanced-subscription");
            subDto.setResourceName("Shield Advanced Subscription");
            subDto.setInstanceType("Auto-renew: " + autoRenew);
            subDto.setState(state);
            subDto.setMonthlyCostUsd(3000.0);

            // Shield has unique subscription-level semantics ($3K/mo fixed) — not routed through
            // RecommendationEngine because the recommendation includes cost-specific context
            if ("ACTIVE".equals(state)) {
                subDto.setRecommendation("Active - $3,000/mo");
                subDto.setRecommendationDetail(
                        "Shield Advanced is active on this account at $3,000/mo plus data transfer charges. " +
                        "This provides advanced DDoS protection, AWS WAF integration, and 24/7 access to the DDoS Response Team (DRT). " +
                        "Verify this subscription is intentional — it's a significant cost that may not be needed for dev/test accounts. " +
                        "Auto-renew is set to " + autoRenew + ".");
            } else {
                subDto.setRecommendation("Review - " + state);
            }

            if (sub.startTime() != null) {
                subDto.setCreatedDate(sub.startTime().toString());
            }
            results.add(subDto);

            // List protected resources
            try {
                ListProtectionsResponse protections = shield.listProtections();
                for (Protection protection : protections.protections()) {
                    ResourceDto protDto = new ResourceDto();
                    protDto.setRegion("global");
                    protDto.setResourceType("Shield Protection");
                    protDto.setResourceId(protection.id());
                    protDto.setResourceName(protection.name() != null ? protection.name() : protection.id());

                    String resourceArn = protection.resourceArn() != null ? protection.resourceArn() : "";
                    String resourceType = classifyProtectedResource(resourceArn);
                    protDto.setInstanceType(resourceType + " / " + shortenArn(resourceArn));
                    protDto.setState("active");
                    protDto.setMonthlyCostUsd(0); // Included in the $3,000/mo subscription
                    protDto.setRecommendation("Active");
                    protDto.setRecommendationDetail(
                            "This resource is protected by Shield Advanced. The protection is included in the $3,000/mo subscription. " +
                            "Verify the protected resource (" + shortenArn(resourceArn) + ") still exists and is in use. " +
                            "Protecting deleted or unused resources wastes a protection slot.");
                    results.add(protDto);
                }
            } catch (Exception e) {
                log.debug("Failed to list Shield protections: {}", e.getMessage());
            }

        } catch (ResourceNotFoundException e) {
            // Shield Advanced not enabled — normal, nothing to report
        } catch (Exception e) {
            log.error("Shield scan failed: {}", e.getMessage());
        }

        return results;
    }

    private String classifyProtectedResource(String arn) {
        if (arn.contains(":elasticloadbalancing:")) return "ELB";
        if (arn.contains(":ec2:") && arn.contains("eip-allocation")) return "Elastic IP";
        if (arn.contains(":ec2:")) return "EC2";
        if (arn.contains(":cloudfront:")) return "CloudFront";
        if (arn.contains(":route53:")) return "Route 53";
        if (arn.contains(":globalaccelerator:")) return "Global Accelerator";
        return "AWS Resource";
    }

    private String shortenArn(String arn) {
        if (arn == null || arn.isEmpty()) return "unknown";
        int lastSlash = arn.lastIndexOf('/');
        int lastColon = arn.lastIndexOf(':');
        int pos = Math.max(lastSlash, lastColon);
        return pos > 0 ? arn.substring(pos + 1) : arn;
    }
}
