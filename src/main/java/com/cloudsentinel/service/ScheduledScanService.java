package com.cloudsentinel.service;

import com.cloudsentinel.dto.AnalysisRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs scheduled scans on a configurable cron schedule.
 *
 * <p>When enabled via {@code cloud-sentinel.scheduled-scan.enabled=true}, this service
 * submits a full scan for each configured AWS profile at the specified cron interval.
 * Disabled by default to avoid unexpected AWS API calls.</p>
 *
 * <p>Uses the same job submission pipeline as manual scans — queuing, deduplication,
 * and audit logging all apply.</p>
 */
@Service
public class ScheduledScanService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledScanService.class);

    private final AnalysisJobService jobService;
    private final AwsProfileService profileService;

    @Value("${cloud-sentinel.scheduled-scan.enabled:false}")
    private boolean enabled;

    @Value("${cloud-sentinel.scheduled-scan.regions:}")
    private String configuredRegions;

    @Value("${cloud-sentinel.scheduled-scan.category:FULL}")
    private String scanCategory;

    @Value("${cloud-sentinel.scheduled-scan.ai-enabled:false}")
    private boolean aiEnabled;

    @Value("${cloud-sentinel.scheduled-scan.ai-provider:bedrock}")
    private String aiProvider;

    public ScheduledScanService(AnalysisJobService jobService, AwsProfileService profileService) {
        this.jobService = jobService;
        this.profileService = profileService;
    }

    /**
     * Runs scheduled scans for all AWS profiles. Default: daily at 6am.
     * Override via {@code cloud-sentinel.scheduled-scan.cron}.
     */
    @Scheduled(cron = "${cloud-sentinel.scheduled-scan.cron:0 0 6 * * *}")
    public void runScheduledScans() {
        if (!enabled) return;

        List<String> profiles = profileService.listProfiles();
        if (profiles.isEmpty()) {
            log.info("Scheduled scan skipped — no AWS profiles configured");
            return;
        }

        log.info("Starting scheduled scan for {} profiles (category={}, ai={})",
                profiles.size(), scanCategory, aiEnabled);

        List<String> regions = configuredRegions.isBlank()
                ? List.of() // empty = all regions
                : List.of(configuredRegions.split(","));

        int submitted = 0;
        for (String profile : profiles) {
            try {
                var request = AnalysisRequest.ofProfile(profile, regions, scanCategory, aiEnabled, aiProvider);
                String jobId = jobService.submit(request, true);
                if (jobId != null) {
                    submitted++;
                    log.info("Scheduled scan submitted for profile '{}': jobId={}", profile, jobId);
                }
            } catch (Exception e) {
                log.warn("Scheduled scan failed for profile '{}': {}", profile, e.getMessage());
            }
        }

        log.info("Scheduled scan complete: {}/{} profiles submitted", submitted, profiles.size());
    }
}
