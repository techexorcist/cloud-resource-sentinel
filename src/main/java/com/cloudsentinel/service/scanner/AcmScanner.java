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
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.CertificateSummary;
import software.amazon.awssdk.services.acm.model.DescribeCertificateRequest;

/**
 * Scans AWS Certificate Manager (ACM) certificates for unused or problematic certificates.
 *
 * <p>Checks certificate status and whether the certificate is in use by any resource.
 * Delegates to {@link RecommendationEngine#getAcmRecommendation} for classification.</p>
 */
@Component
public class AcmScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(AcmScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public AcmScanner(PricingService pricingService, RecommendationEngine engine) {
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
     * Scans ACM certificates in the given region.
     *
     * <p>Calls {@code listCertificates} and {@code describeCertificate} per certificate.
     * Errors on individual certificates are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered certificates with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var acm = ReadOnlyAwsClientFactory.build(AcmClient.builder(), creds, Region.of(region))) {
            for (CertificateSummary cert : acm.listCertificates().certificateSummaryList()) {
                try {
                    results.add(buildDto(acm, cert, region));
                } catch (Exception e) {
                    log.warn("Failed to process ACM certificate {}: {}", cert.certificateArn(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("ACM scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single ACM certificate.
     *
     * <p>Fetches certificate detail to determine status, type, key algorithm, and in-use state.
     * Cost is always $0 (ACM public certificates are free).</p>
     */
    private ResourceDto buildDto(AcmClient acm, CertificateSummary cert, String region) {
        CertificateDetail detail = acm.describeCertificate(DescribeCertificateRequest.builder()
                .certificateArn(cert.certificateArn()).build()).certificate();

        String status = detail.statusAsString();
        String certType = detail.type() != null ? detail.typeAsString() : "Unknown";
        String keyAlgorithm = detail.keyAlgorithm() != null ? detail.keyAlgorithmAsString() : "Unknown";

        boolean inUse = detail.inUseBy() != null && !detail.inUseBy().isEmpty();
        String recommendation = engine.getAcmRecommendation(status, inUse);

        ResourceDto dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("ACM Certificate");
        dto.setResourceId(cert.certificateArn());
        dto.setResourceName(detail.domainName());
        dto.setInstanceType(certType + " / " + keyAlgorithm);
        dto.setState(status);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (detail.createdAt() != null) {
            dto.setCreatedDate(detail.createdAt().toString());
        }

        return dto;
    }
}
