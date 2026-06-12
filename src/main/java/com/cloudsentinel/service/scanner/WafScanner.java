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
import software.amazon.awssdk.services.wafv2.Wafv2Client;
import software.amazon.awssdk.services.wafv2.model.Scope;
import software.amazon.awssdk.services.wafv2.model.WebACL;
import software.amazon.awssdk.services.wafv2.model.WebACLSummary;

/**
 * Scans WAF Web ACLs for unassociated or rule-less ACLs.
 *
 * <p>Checks rule count and whether the ACL is associated with any resources.
 * Scans regional-scope ACLs only. Delegates to
 * {@link RecommendationEngine#getWafRecommendation} for classification.</p>
 */
@Component
public class WafScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(WafScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public WafScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans WAF Web ACLs (regional scope) in the given region.
     *
     * <p>Calls {@code listWebACLs}, {@code getWebACL}, and {@code listResourcesForWebACL}
     * per ACL. Errors on individual ACLs are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered WAF Web ACLs with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var waf = ReadOnlyAwsClientFactory.build(Wafv2Client.builder(), creds, Region.of(region))) {
            List<WebACLSummary> acls = waf.listWebACLs(r -> r.scope(Scope.REGIONAL)).webACLs();

            for (WebACLSummary aclSummary : acls) {
                try {
                    results.add(buildDto(waf, aclSummary, region));
                } catch (Exception e) {
                    log.warn("Failed to process WAF Web ACL {}: {}", aclSummary.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("WAF scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single WAF Web ACL.
     *
     * <p>Fetches rule count and checks resource associations. Cost is based on ACL + rule count.</p>
     */
    private ResourceDto buildDto(Wafv2Client waf, WebACLSummary aclSummary, String region) {
        WebACL webAcl = waf.getWebACL(r -> r.name(aclSummary.name())
                .scope(Scope.REGIONAL)
                .id(aclSummary.id())).webACL();

        int ruleCount = webAcl.rules() != null ? webAcl.rules().size() : 0;

        List<String> associatedResources = new ArrayList<>();
        try {
            associatedResources = waf.listResourcesForWebACL(r -> r.webACLArn(aclSummary.arn())).resourceArns();
        } catch (Exception e) {
            log.debug("Cannot check associations for WAF ACL {}: {}", aclSummary.name(), e.getMessage());
        }

        String recommendation = engine.getWafRecommendation(!associatedResources.isEmpty(), ruleCount);

        double cost = this.pricingService.getWafPrice(ruleCount, region);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("WAF");
        dto.setResourceId(webAcl.id());
        dto.setResourceName(webAcl.name());
        dto.setInstanceType("Regional / " + ruleCount + " rules");
        dto.setState("active");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);

        return dto;
    }
}
