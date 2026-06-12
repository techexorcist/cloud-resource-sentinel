package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;
import com.cloudsentinel.service.pricing.PricingService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Crawler;
import software.amazon.awssdk.services.glue.model.Job;
import software.amazon.awssdk.services.glue.model.JobRun;

/**
 * Scans AWS Glue jobs and crawlers for idle or unused ETL resources.
 *
 * <p>Checks last run date and days since last execution for jobs, and last crawl
 * time for crawlers. Delegates to {@link RecommendationEngine#getGlueJobRecommendation}
 * and {@link RecommendationEngine#getGlueCrawlerRecommendation} for classification.</p>
 */
@Component
public class GlueScanner implements ResourceScanner {
    private static final Logger log = LoggerFactory.getLogger(GlueScanner.class);
    private final PricingService pricingService;
    private final RecommendationEngine engine;

    public GlueScanner(PricingService pricingService, RecommendationEngine engine) {
        this.pricingService = pricingService;
        this.engine = engine;
    }

    /**
     * Scans Glue jobs and crawlers in the given region.
     *
     * <p>Calls {@code getJobs} and {@code getJobRuns} per job, plus {@code getCrawlers}.
     * Errors on individual jobs or crawlers are logged and skipped.</p>
     *
     * @param creds AWS credentials provider
     * @param region the AWS region to scan
     * @return list of discovered Glue jobs and crawlers with recommendations
     */
    @Override
    public List<ResourceDto> scan(AwsCredentialsProvider creds, String region) {
        List<ResourceDto> results = new ArrayList<>();

        try (var glue = ReadOnlyAwsClientFactory.build(GlueClient.builder(), creds, Region.of(region))) {

            for (Job job : glue.getJobs(r -> {}).jobs()) {
                try {
                    results.add(buildJobDto(job, glue, region));
                } catch (Exception e) {
                    log.warn("Failed to process Glue job {}: {}", job.name(), e.getMessage());
                }
            }

            for (Crawler crawler : glue.getCrawlers(r -> {}).crawlers()) {
                try {
                    results.add(buildCrawlerDto(crawler, region));
                } catch (Exception e) {
                    log.warn("Failed to process Glue crawler {}: {}", crawler.name(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Glue scan failed in region {}: {}", region, e.getMessage());
        }

        return results;
    }

    /**
     * Builds a ResourceDto for a single Glue job.
     *
     * <p>Fetches the most recent job run to determine last execution date and estimate
     * monthly DPU-hour cost. Jobs with no runs report $0 cost.</p>
     */
    private ResourceDto buildJobDto(Job job, GlueClient glue, String region) {
        String name = job.name();
        String workerType = job.workerTypeAsString() != null ? job.workerTypeAsString() : "Standard";
        int numberOfWorkers = job.numberOfWorkers() != null ? job.numberOfWorkers() : 0;
        List<JobRun> runs = glue.getJobRuns(r -> r.jobName(name).maxResults(1)).jobRuns();
        String lastRunDate = "Never";
        boolean hasRuns = !runs.isEmpty();
        long daysSinceLastRun = 0;
        if (hasRuns) {
            JobRun lastRun = runs.getFirst();
            Instant completedOn = lastRun.completedOn();
            if (completedOn != null) {
                lastRunDate = completedOn.toString();
                daysSinceLastRun = ChronoUnit.DAYS.between(completedOn, Instant.now());
            }
        }
        String recommendation = engine.getGlueJobRecommendation(daysSinceLastRun, hasRuns);

        double estimatedMonthlyCost = 0.0;
        if (!runs.isEmpty()) {
            JobRun lastRun = runs.getFirst();
            int executionTimeSec = lastRun.executionTime() != null ? lastRun.executionTime() : 0;
            int dpus = numberOfWorkers > 0 ? numberOfWorkers : 2;
            double dpuHoursPerRun = dpus * (executionTimeSec / 3600.0);
            estimatedMonthlyCost = pricingService.getGlueDpuPrice(dpuHoursPerRun * 4, region);
        }

        String description = workerType + " x " + numberOfWorkers + " / Last run: " + lastRunDate;
        String state = job.executionProperty() != null ? "Configured" : "Unknown";
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Glue");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(state);
        dto.setMonthlyCostUsd(estimatedMonthlyCost);
        dto.setRecommendation(recommendation);
        if (job.createdOn() != null) {
            dto.setCreatedDate(job.createdOn().toString());
        }

        return dto;
    }

    /**
     * Builds a ResourceDto for a single Glue crawler.
     *
     * <p>Checks days since last crawl from the crawler's last crawl start time.
     * Cost is $0 (crawlers are charged per DPU-second while running).</p>
     */
    private ResourceDto buildCrawlerDto(Crawler crawler, String region) {
        String name = crawler.name();
        String state = crawler.stateAsString();
        String description = "Crawler / " + state;
        Instant lastCrawl = crawler.lastCrawl() != null ? crawler.lastCrawl().startTime() : null;
        long daysSinceLastCrawl = lastCrawl != null ? ChronoUnit.DAYS.between(lastCrawl, Instant.now()) : Long.MAX_VALUE;
        String recommendation = engine.getGlueCrawlerRecommendation(state, daysSinceLastCrawl);

        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType("Glue");
        dto.setResourceId(name);
        dto.setResourceName(name);
        dto.setInstanceType(description);
        dto.setState(state);
        dto.setMonthlyCostUsd(0.0);
        dto.setRecommendation(recommendation);
        if (crawler.creationTime() != null) {
            dto.setCreatedDate(crawler.creationTime().toString());
        }

        return dto;
    }
}
