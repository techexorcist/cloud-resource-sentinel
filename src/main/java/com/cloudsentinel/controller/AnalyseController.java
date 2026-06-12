package com.cloudsentinel.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.dto.AnalysisRequest;
import com.cloudsentinel.service.AnalysisJobService;
import com.cloudsentinel.service.AnalysisJobService.JobStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Analysis API group, handling job lifecycle operations for resource scans.
 *
 * <p>This controller manages the scan workflow: submitting individual and batch analysis jobs,
 * monitoring job progress, and cancelling jobs. All endpoints return JSON responses.</p>
 *
 * <p>Key behaviors:</p>
 * <ul>
 *   <li>Scans execute sequentially (1 at a time) to avoid overloading the AI model; others queue automatically.</li>
 *   <li>Individual scans are limited to 7 queued jobs; batch scans bypass this limit.</li>
 *   <li>Duplicate prevention: submitting a scan for a profile with an active job returns the existing job ID.</li>
 * </ul>
 *
 * @see com.cloudsentinel.service.AnalysisJobService
 */
@RestController
@Tag(name = "Analysis")
public class AnalyseController {

    private final AnalysisJobService jobService;

    /**
     * Constructs the analysis controller with its required service dependency.
     *
     * @param jobService the analysis job service for submitting/tracking/cancelling scans
     */
    public AnalyseController(AnalysisJobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Submits a resource scan for a single AWS profile.
     *
     * <p>POST {@code /analyse}</p>
     *
     * <p>If a scan is already running for the same profile, returns the existing job ID with
     * {@code already_running: true} instead of creating a duplicate. Returns HTTP 429 if
     * the queue is full (7 individual scan limit).</p>
     *
     * @param request the analysis request containing profile name, regions, scan type, and AI provider
     * @return 200 with {@code job_id} on success, 200 with {@code already_running} if duplicate,
     *         or 429 with {@code detail} and {@code active_jobs} if queue is full
     */
    @Operation(summary = "Submit analysis job", description = "Submits a resource scan for a single AWS profile. Returns existing job ID if a scan is already running for the same profile. Returns 429 if all slots are full.")
    @PostMapping("/analyse")
    public ResponseEntity<Map<String, Object>> analyse(@RequestBody AnalysisRequest request) {
        request.validate();

        // Check if a scan is already running for this profile/account
        String profileName = request.profileName() != null ? request.profileName() : "default";
        String existingJobId = jobService.getActiveJobForProfile(profileName);
        if (existingJobId != null) {
            return ResponseEntity.ok(Map.of(
                    "job_id", (Object) existingJobId,
                    "already_running", (Object) true,
                    "message", (Object) ("Scan already in progress for " + profileName)
            ));
        }

        if (jobService.isQueueFull()) {
            var activeJobs = jobService.getActiveJobs().stream().map(job -> Map.of(
                    "job_id", (Object) job.jobId,
                    "account_id", (Object) job.accountId,
                    "profile_name", (Object) job.profileName,
                    "phase", (Object) job.phase,
                    "progress", (Object) job.progress
            )).toList();
            return ResponseEntity.status(429).body(Map.of(
                    "detail", "Queue is full (" + jobService.getActiveJobs().size() + "/" + jobService.maxQueueSize() + " slots). Wait for existing jobs to complete.",
                    "active_jobs", activeJobs
            ));
        }
        String jobId = jobService.submit(request);
        return ResponseEntity.ok(Map.of("job_id", (Object) jobId));
    }

    /**
     * Cancels a specific analysis job by its ID.
     *
     * <p>POST {@code /analyse/cancel?jobId=...}</p>
     *
     * @param jobId the unique job identifier to cancel
     * @return 200 with {@code status: "cancelled"} on success, or 400 if the job was not found or already finished
     */
    @Operation(summary = "Cancel a running job", description = "Cancels a specific analysis job by its ID.")
    @PostMapping("/analyse/cancel")
    public ResponseEntity<Map<String, Object>> cancelJob(@RequestParam String jobId) {
        boolean cancelled = jobService.cancel(jobId);
        if (cancelled) {
            return ResponseEntity.ok(Map.of("status", "cancelled", "job_id", jobId));
        }
        return ResponseEntity.badRequest().body(Map.of(
                "detail", "Job not found or already finished",
                "job_id", jobId
        ));
    }

    /**
     * Cancels all running jobs and clears finished jobs from the queue.
     *
     * <p>POST {@code /analyse/cancel-all}</p>
     *
     * @return 200 with {@code cancelled} count, {@code cleared} count, and a summary {@code message}
     */
    @PostMapping("/analyse/cancel-all")
    public ResponseEntity<Map<String, Object>> cancelAll() {
        int cancelled = jobService.cancelAll();
        int cleared = jobService.clearFinished();
        return ResponseEntity.ok(Map.of(
                "cancelled", cancelled,
                "cleared", cleared,
                "message", cancelled + " job(s) cancelled, " + cleared + " finished job(s) cleared"
        ));
    }

    /**
     * Returns the current status of a specific analysis job.
     *
     * <p>GET {@code /analyse/job?jobId=...}</p>
     *
     * <p>When the job phase is {@code "complete"}, the response includes the full scan report.
     * Returns 404 if the job ID is not found.</p>
     *
     * @param jobId the unique job identifier
     * @return 200 with job status (job_id, account_id, phase, message, progress, and optionally report),
     *         or 404 if the job was not found
     */
    @Operation(summary = "Get job status", description = "Returns the current phase, progress percentage, and message for a specific job. Returns the full report when phase is 'complete'.")
    @GetMapping("/analyse/job")
    public ResponseEntity<Map<String, Object>> jobStatus(@RequestParam String jobId) {
        JobStatus status = jobService.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.status(404).body(
                    Map.of("phase", "unknown", "message", "Job not found", "progress", 0));
        }
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("job_id", status.jobId);
        result.put("account_id", status.accountId);
        result.put("phase", status.phase);
        result.put("message", status.message);
        result.put("progress", status.progress);
        if ("complete".equals(status.phase) && status.report != null) {
            result.put("report", status.report);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Lists all analysis jobs with their current status and queue capacity information.
     *
     * <p>GET {@code /analyse/jobs}</p>
     *
     * @return a map containing {@code jobs} (list of job statuses), {@code capacity} (max queue size),
     *         {@code active_count}, and {@code at_capacity} (boolean)
     */
    @Operation(summary = "List all jobs", description = "Returns all analysis jobs with their status, capacity info, and active count.")
    @GetMapping("/analyse/jobs")
    public Map<String, Object> listJobs() {
        var jobsList = jobService.getAllJobs().stream().map(job -> {
            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("job_id", job.jobId);
            entry.put("account_id", job.accountId);
            entry.put("profile_name", job.profileName);
            entry.put("phase", job.phase);
            entry.put("message", job.message);
            entry.put("progress", job.progress);
            return (Map<String, Object>) entry;
        }).toList();
        return Map.of(
                "jobs", jobsList,
                "capacity", jobService.maxQueueSize(),
                "active_count", jobService.getActiveJobs().size(),
                "at_capacity", jobService.isQueueFull()
        );
    }

    /**
     * Submits analysis jobs for multiple profiles in a single batch request.
     *
     * <p>POST {@code /analyse/batch}</p>
     *
     * <p>All jobs are queued; up to 3 run concurrently while the rest wait. Profiles with
     * active scans are skipped rather than rejected. Batch submissions bypass the individual
     * queue limit of 7 but are capped at {@code max(maxConcurrentJobs * 10, 50)}.</p>
     *
     * @param requests the list of analysis requests, one per profile
     * @return 200 with {@code submitted}, {@code skipped}, {@code failed} lists and counts,
     *         or 429 if the batch size or total queue would be exceeded
     */
    @Operation(summary = "Batch scan all profiles", description = "Submits analysis jobs for multiple profiles in a single request. All jobs are queued; 3 run concurrently, rest wait. Skips profiles with active scans.")
    @PostMapping("/analyse/batch")
    public ResponseEntity<Map<String, Object>> batchAnalyse(@RequestBody java.util.List<AnalysisRequest> requests) {
        int maxBatchSize = Math.max(jobService.maxConcurrentJobs() * 10, 50);
        if (requests.size() > maxBatchSize) {
            return ResponseEntity.status(429).body(Map.of(
                    "detail", "Batch size " + requests.size() + " exceeds maximum of " + maxBatchSize,
                    "submitted_count", 0,
                    "skipped_count", 0
            ));
        }
        int activeCount = jobService.getActiveJobs().size();
        if (activeCount + requests.size() > maxBatchSize) {
            return ResponseEntity.status(429).body(Map.of(
                    "detail", "Too many jobs already queued (" + activeCount + " active). Try again later.",
                    "submitted_count", 0,
                    "skipped_count", 0
            ));
        }
        // Cancel all existing jobs and clear them — batch always starts fresh
        jobService.cancelAll();
        jobService.clearFinished();

        var submitted = new java.util.ArrayList<Map<String, Object>>();
        var skipped = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<Map<String, String>>();

        for (AnalysisRequest request : requests) {
            String profileName = request.profileName() != null ? request.profileName() : "default";
            try {
                request.validate();

                String jobId = jobService.submit(request, true); // batch bypasses queue limit
                if (jobId == null) {
                    skipped.add(profileName);
                } else {
                    submitted.add(Map.of("profile_name", profileName, "job_id", jobId));
                }
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (reason.length() > 150) reason = reason.substring(0, 150) + "...";
                failed.add(Map.of("profile", profileName, "reason", reason));
            }
        }

        var parts = new java.util.ArrayList<String>();
        if (!submitted.isEmpty()) parts.add(submitted.size() + " scan(s) queued");
        if (!skipped.isEmpty()) parts.add(skipped.size() + " already running");
        if (!failed.isEmpty()) parts.add(failed.size() + " failed to start");

        return ResponseEntity.ok(Map.of(
                "submitted", submitted,
                "skipped", skipped,
                "failed", failed,
                "submitted_count", submitted.size(),
                "skipped_count", skipped.size(),
                "failed_count", failed.size(),
                "message", parts.isEmpty() ? "No scans submitted" : String.join(", ", parts)
        ));
    }
}
