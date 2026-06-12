package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.ImageDetail;
import software.amazon.awssdk.services.ecr.model.Repository;

/**
 * Scans ECR repositories for empty or stale image repositories.
 *
 * <p>Checks image count, total size, and whether all images are older than 90 days.
 * Delegates to {@link RecommendationEngine#getEcrRecommendation} for classification.</p>
 */
@Component
public class EcrScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(EcrScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public EcrScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans ECR repositories in the given region.
     *
     * <p>Calls {@code describeRepositories} and {@code describeImages} per repository.
     * Errors on individual repositories are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered ECR repositories with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var ecr = ReadOnlyAwsClientFactory.build(EcrClient.builder(), creds, Region.of(region))) {

            for (Repository repo : ecr.describeRepositories().repositories()) {
                try {
                    results.add(buildDto(repo, ecr, region));
                } catch (Exception e) {
                    log.warn("Failed to process ECR repository {}: {}", repo.repositoryName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("ECR scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single ECR repository.
     *
     * <p>Counts images, computes total size, and checks whether all images are older
     * than 90 days (stale). Cost is based on total storage in GB.</p>
     */
    private ResourceDto buildDto(Repository repo, EcrClient ecr, String region) {
        String repoName = repo.repositoryName();
        String repoArn = repo.repositoryArn();

        List<ImageDetail> images;
        try {
            images = ecr.describeImages(r -> r.repositoryName(repoName)).imageDetails();
        } catch (Exception e) {
            log.warn("Failed to describe images for ECR repo {}: {}", repoName, e.getMessage());
            images = List.of();
        }

        int imageCount = images.size();
        long totalSizeBytes = images.stream().mapToLong(img -> img.imageSizeInBytes() != null ? img.imageSizeInBytes() : 0L).sum();
        double totalSizeMb = (double) totalSizeBytes / 1048576.0;
        boolean hasStaleImages = false;
        if (!images.isEmpty()) {
            Instant ninetyDaysAgo = Instant.now().minus(Duration.ofDays(90L));
            boolean allOld = images.stream().allMatch(img -> img.imagePushedAt() != null && img.imagePushedAt().isBefore(ninetyDaysAgo));
            if (allOld) {
                hasStaleImages = true;
            }
        }

        String recommendation = engine.getEcrRecommendation(imageCount, hasStaleImages);

        double sizeGb = totalSizeBytes / 1_073_741_824.0;
        double cost = Math.round(sizeGb * pricingService.getEcrPricePerGb(region) * 100.0) / 100.0;
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("ECR");
        dto.setResourceId(repoArn);
        dto.setResourceName(repoName);
        dto.setInstanceType(imageCount + " images / " + String.format("%.1fMB", totalSizeMb));
        dto.setState("active");
        dto.setMonthlyCostUsd(cost);
        dto.setRecommendation(recommendation);
        if (repo.createdAt() != null) {
            dto.setCreatedDate(repo.createdAt().toString());
        }
        return dto;
    }
}
