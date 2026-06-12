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
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DescribeKeyRequest;
import software.amazon.awssdk.services.kms.model.GetKeyRotationStatusRequest;
import software.amazon.awssdk.services.kms.model.KeyListEntry;
import software.amazon.awssdk.services.kms.model.KeyMetadata;

/**
 * Scans customer-managed KMS keys for rotation compliance and key state.
 *
 * <p>Checks whether automatic key rotation is enabled and the key state.
 * Skips AWS-managed keys. Delegates to {@link RecommendationEngine#getKmsRecommendation}
 * for classification.</p>
 */
@Component
public class KmsScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(KmsScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public KmsScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    @Override
    public ResourceScanner.ScanCategory category() {
        return ResourceScanner.ScanCategory.SECURITY_GOVERNANCE;
    }

    @Override
    public FindingType findingType() {
        return FindingType.SECURITY;
    }

    /**
     * Scans customer-managed KMS keys in the given region.
     *
     * <p>Calls {@code listKeysPaginator}, then {@code describeKey} and
     * {@code getKeyRotationStatus} per key. Skips AWS-managed keys. Errors on
     * individual keys are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered KMS keys with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var kms = ReadOnlyAwsClientFactory.build(KmsClient.builder(), creds, Region.of(region))) {

            kms.listKeysPaginator().keys().forEach(key -> {
                try {
                    ResourceDto dto = buildDto(kms, key, region);
                    if (dto != null) {
                        results.add(dto);
                    }
                } catch (Exception e) {
                    log.warn("Failed to process KMS key {}: {}", key.keyId(), e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("KMS scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single customer-managed KMS key.
     *
     * <p>Returns {@code null} for AWS-managed keys. Checks rotation status and key state.
     * Only enabled keys incur the monthly key cost.</p>
     */
    private ResourceDto buildDto(KmsClient kms, KeyListEntry key, String region) {
        KeyMetadata metadata = kms.describeKey((DescribeKeyRequest) DescribeKeyRequest.builder().keyId(key.keyId()).build()).keyMetadata();
        if ("AWS".equalsIgnoreCase(metadata.keyManagerAsString())) {
            return null;
        }

        boolean rotationEnabled = false;
        try {
            rotationEnabled = kms.getKeyRotationStatus((GetKeyRotationStatusRequest) GetKeyRotationStatusRequest.builder().keyId(key.keyId()).build()).keyRotationEnabled();
        } catch (Exception e) {
            log.debug("Cannot check rotation for key {}: {}", key.keyId(), e.getMessage());
        }

        String keyState = metadata.keyStateAsString();
        String recommendation = engine.getKmsRecommendation(rotationEnabled, keyState);
        double cost = "Enabled".equalsIgnoreCase(keyState) ? pricingService.getKmsKeyPrice(region) : 0.0;
        String description = String.format("%s / Rotation: %s", metadata.keySpec() != null ? metadata.keySpecAsString() : "SYMMETRIC_DEFAULT", rotationEnabled ? "enabled" : "disabled");
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("KMS");
        dto.setResourceId(key.keyId());
        dto.setResourceName(metadata.description() != null && !metadata.description().isBlank() ? metadata.description() : key.keyId());
        dto.setInstanceType(description);
        dto.setState(keyState);
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (metadata.creationDate() != null) {
            dto.setCreatedDate(metadata.creationDate().toString());
        }

        return dto;
    }
}
