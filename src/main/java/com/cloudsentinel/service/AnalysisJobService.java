package com.cloudsentinel.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.AiFilteringDto;
import com.cloudsentinel.dto.AiInsightDto;
import com.cloudsentinel.dto.AnalysisRequest;
import com.cloudsentinel.dto.AnalysisResponse;
import com.cloudsentinel.dto.ScanReportDto;

/**
 * Manages the lifecycle of analysis jobs from submission through completion, cancellation, or failure.
 *
 * <p>This service enforces sequential scan execution: only one scan runs at a time to avoid
 * overloading the AI model and AWS API rate limits. Additional scan requests are queued
 * automatically, with a configurable maximum queue depth of 7 for individual scans.
 * "Scan All Profiles" batch submissions bypass the queue limit.</p>
 *
 * <h3>Job Lifecycle</h3>
 * <p>Each job progresses through well-defined phases with corresponding progress boundaries:</p>
 * <ul>
 *   <li><strong>queued</strong> (0%) — job accepted, waiting for executor slot.</li>
 *   <li><strong>scanning</strong> (10-60%) — AWS resource scanners running in parallel.
 *       Progress within this range is driven by scanner task completion callbacks.
 *       Formula: {@code 10 + (completedTasks / totalTasks) * 50}, capped at 60.</li>
 *   <li><strong>ai</strong> (70-89%) — AI analysis running (if enabled). Progress within
 *       this range is driven by batch completion: {@code 70 + (completedBatches / totalBatches) * 20},
 *       capped at 89.</li>
 *   <li><strong>saving</strong> (90%) — persisting the report to disk.</li>
 *   <li><strong>complete</strong> (100%) — job finished successfully.</li>
 *   <li><strong>error</strong> (100%) — job terminated due to an unrecoverable error.</li>
 *   <li><strong>cancelled</strong> (100%) — job was cancelled by the user.</li>
 * </ul>
 *
 * <h3>Concurrency Model</h3>
 * <ul>
 *   <li>The {@link #submit(AnalysisRequest, boolean)} method is {@code synchronized} to make
 *       the duplicate-check-and-insert operation atomic. Without synchronization, two concurrent
 *       submissions for the same account could both pass the duplicate check and both be inserted.</li>
 *   <li>Job status fields ({@code phase}, {@code message}, {@code progress}) are {@code volatile}
 *       for visibility across threads without requiring synchronization on reads.</li>
 *   <li>Finished jobs are automatically evicted from memory after 1 hour by a daemon scheduled
 *       executor that runs every 5 minutes.</li>
 * </ul>
 *
 * <h3>Graceful Shutdown</h3>
 * <p>{@link #cancelAll()} replaces the executor pool to free threads stuck on blocking I/O.
 * It first signals all active jobs via their {@code cancelled} flag, then shuts down the old
 * executor with a 10-second grace period before forcing shutdown.</p>
 *
 * @see ResourceAnalyzer
 * @see AiAnalysisService
 * @see ReportService
 * @see AuditService
 */
@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    private final ResourceAnalyzer analyzer;
    private final AiAnalysisService aiAnalysisService;
    private final ReportService reportService;
    private final AuditService auditService;
    /** Only one scan runs at a time to avoid overloading the AI model and AWS API rate limits. */
    private static final int MAX_CONCURRENT_SCANS = 1;
    /** Maximum number of individual (non-batch) scans that can be queued simultaneously. */
    private static final int MAX_QUEUE_SIZE = 7;
    /** Finished jobs are evicted from memory after this duration (1 hour). */
    private static final long JOB_RETENTION_MS = 3600_000; // 1 hour
    /** The executor is volatile because {@link #cancelAll()} replaces it with a fresh instance. */
    private volatile ExecutorService executor = Executors.newFixedThreadPool(MAX_CONCURRENT_SCANS);
    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    /**
     * Constructs the job service with all required collaborators.
     *
     * <p>Also starts a daemon scheduled executor that evicts finished jobs older than
     * {@link #JOB_RETENTION_MS} every 5 minutes to prevent unbounded memory growth.</p>
     *
     * @param analyzer         the resource analyzer that performs the actual AWS scanning
     * @param aiAnalysisService the AI service for generating insights on scanned resources
     * @param reportService    the report persistence service
     * @param auditService     the audit logging service
     */
    private final Counter scanCompletedCounter;
    private final Counter scanFailedCounter;
    private final Timer scanDurationTimer;
    private final Counter resourcesFoundCounter;
    private final Counter aiTokensUsedCounter;

    public AnalysisJobService(ResourceAnalyzer analyzer, AiAnalysisService aiAnalysisService,
                              ReportService reportService, AuditService auditService,
                              MeterRegistry meterRegistry) {
        this.analyzer = analyzer;
        this.aiAnalysisService = aiAnalysisService;
        this.reportService = reportService;
        this.auditService = auditService;
        this.scanCompletedCounter = Counter.builder("sentinel.scans.completed")
                .description("Total completed scans").register(meterRegistry);
        this.scanFailedCounter = Counter.builder("sentinel.scans.failed")
                .description("Total failed scans").register(meterRegistry);
        this.scanDurationTimer = Timer.builder("sentinel.scans.duration")
                .description("Scan duration").register(meterRegistry);
        this.resourcesFoundCounter = Counter.builder("sentinel.resources.found")
                .description("Total resources discovered across all scans").register(meterRegistry);
        this.aiTokensUsedCounter = Counter.builder("sentinel.ai.tokens.total")
                .description("Total AI tokens consumed across all scans").register(meterRegistry);
        // Periodically evict finished jobs older than 1 hour
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "job-cleanup");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::evictStaleJobs, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Removes finished jobs (complete, error, or cancelled) that have been in terminal state
     * for longer than {@link #JOB_RETENTION_MS}.
     *
     * <p>Called both periodically by the scheduled cleanup executor and eagerly at the start
     * of each {@link #submit} call to prevent memory buildup. Job status fields are volatile,
     * so reads are visibility-safe without synchronization.</p>
     */
    private void evictStaleJobs() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(entry -> {
            JobStatus status = entry.getValue();
            // Fields are volatile — reads are visibility-safe without synchronization
            boolean finished = "complete".equals(status.phase) || "error".equals(status.phase) || "cancelled".equals(status.phase);
            return finished && status.completedAt > 0 && (now - status.completedAt) > JOB_RETENTION_MS;
        });
    }

    /**
     * Gracefully shuts down the scan executor on application shutdown.
     *
     * <p>Waits up to 30 seconds for in-flight scans to complete before forcing shutdown.
     * This ensures AWS SDK connections are properly drained and reports are saved.</p>
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down analysis job executor...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Executor did not terminate within 30 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Analysis job executor shut down");
    }

    /**
     * Returns the job ID of an active (non-terminal) job for the given profile/account, or
     * {@code null} if no such job exists.
     *
     * <p>The profile name is resolved to an account ID via {@link ReportService#resolveAccountId(String)}
     * before matching. A job is considered active if its phase is not {@code complete}, {@code error},
     * or {@code cancelled}.</p>
     *
     * @param profileName the AWS profile name to check (defaults to {@code "default"} if null)
     * @return the job ID of an active job for the account, or {@code null}
     */
    public String getActiveJobForProfile(String profileName) {
        String accountId = reportService.resolveAccountId(profileName != null ? profileName : "default");
        return jobs.values().stream()
                .filter(j -> accountId.equals(j.accountId) &&
                        !"complete".equals(j.phase) && !"error".equals(j.phase) && !"cancelled".equals(j.phase))
                .map(j -> j.jobId)
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the total number of jobs currently tracked in memory, including finished ones
     * that have not yet been evicted.
     *
     * @return total job count
     */
    public int totalJobCount() {
        return jobs.size();
    }

    /**
     * Checks whether the job queue has reached its maximum capacity for individual scans.
     *
     * @return {@code true} if the number of active jobs equals or exceeds {@link #MAX_QUEUE_SIZE}
     */
    public boolean isQueueFull() {
        return getActiveJobs().size() >= MAX_QUEUE_SIZE;
    }

    /**
     * Returns the configured maximum queue size for individual (non-batch) scans.
     *
     * @return the maximum number of jobs that can be queued simultaneously
     */
    public int maxQueueSize() {
        return MAX_QUEUE_SIZE;
    }

    /**
     * Convenience overload that submits a job with queue capacity enforcement.
     *
     * @param request the analysis request
     * @return the generated job ID
     * @throws IllegalStateException if the queue is full or a duplicate job is already running
     */
    public String submit(AnalysisRequest request) {
        return submit(request, false);
    }

    /**
     * Submits an analysis job for asynchronous execution.
     *
     * <p>This method is {@code synchronized} to make the duplicate-check-and-insert operation
     * atomic. Without this, two concurrent submissions for the same AWS account could both pass
     * the duplicate check and both be inserted into the job map.</p>
     *
     * <p>The job ID is generated as {@code accountId_epochMillis} for uniqueness and traceability.
     * The submitted task progresses through the phases documented in the class-level Javadoc.</p>
     *
     * <p>If AI analysis is enabled but fails, the report is still saved with scan data only
     * (no AI insights). The AI filtering DTO will record the model that was attempted.</p>
     *
     * @param request        the analysis request specifying profile, regions, scan category, and AI options
     * @param skipQueueCheck if {@code true}, bypasses the queue capacity check (used by batch scan)
     * @return the generated job ID for tracking via {@link #getStatus(String)}
     * @throws IllegalStateException if the queue is full (and {@code skipQueueCheck} is false)
     *                               or a scan is already running for the same account
     */
    public synchronized String submit(AnalysisRequest request, boolean skipQueueCheck) {
        // Auto-clean finished jobs to prevent memory buildup
        evictStaleJobs();

        if (!skipQueueCheck && isQueueFull()) {
            throw new IllegalStateException("Job queue is full (" + getActiveJobs().size() + "/" + MAX_QUEUE_SIZE + " slots). Wait for existing jobs to complete.");
        }
        String profileName = request.profileName() != null ? request.profileName() : "default";
        String accountId = reportService.resolveAccountId(profileName);

        // Atomic duplicate check — always runs inside synchronized block
        boolean alreadyRunning = jobs.values().stream()
                .anyMatch(j -> accountId.equals(j.accountId) &&
                        !"complete".equals(j.phase) && !"error".equals(j.phase) && !"cancelled".equals(j.phase));
        if (alreadyRunning) {
            if (skipQueueCheck) {
                return null; // Batch: silently skip duplicate, controller handles it
            }
            throw new IllegalStateException("A scan is already running for account " + accountId);
        }

        String jobId = accountId + "_" + Instant.now().toEpochMilli();

        JobStatus status = new JobStatus(jobId, accountId, profileName);
        jobs.put(jobId, status);

        status.future = executor.submit(() -> {
            long startMs = System.currentTimeMillis();
            try {
                if (status.cancelled.get()) return;
                status.phase = "scanning";
                status.message = "Scanning AWS resources...";
                status.progress = 10;

                AnalysisResponse response = analyzer.analyzeAllResources(request, status.cancelled, new ResourceAnalyzer.ScanProgressCallback() {
                    @Override
                    public void onProgress(int done, int total) {
                        int safeDone = Math.min(done, total);
                        int safeTotal = Math.max(total, 1);
                        int scanProgress = Math.min(60, 10 + (int) ((safeDone / (double) safeTotal) * 50));
                        status.progress = scanProgress;
                        int pct = (int) ((safeDone / (double) safeTotal) * 100);
                        status.message = "Scanning resource types across regions — " + done + "/" + total + " complete (" + pct + "%)";
                    }

                    @Override
                    public void onStatus(String message) {
                        status.message = message;
                    }
                });

                if (status.cancelled.get()) {
                    log.info("Job {} cancelled during scanning", jobId);
                    auditService.logScan(new AuditService.AuditEntry(
                            Instant.now(), accountId, profileName, "cancelled",
                            0, 0, 0, 0,
                            request.isAiFilterEnabled(), request.resolvedAiProvider(),
                            0, System.currentTimeMillis() - startMs,
                            null, null, null, null, 0,
                            request.scanCategory(), 0, 0, 0
                    ));
                    return;
                }

                if (status.cancelled.get()) return;
                status.progress = 60;
                status.message = "Found " + response.getTotalResources() + " resources";

                if (response.getCredentialError() != null) {
                    status.phase = "error";
                    status.message = response.getCredentialError();
                    status.progress = 100;
                    status.completedAt = System.currentTimeMillis();
                    auditService.logScan(new AuditService.AuditEntry(
                            Instant.now(), accountId, profileName, "credential_error",
                            0, 0, 0, 0,
                            request.isAiFilterEnabled(), request.resolvedAiProvider(),
                            0, System.currentTimeMillis() - startMs,
                            null, null, null, null, 0,
                            request.scanCategory(), 0, 0, 0
                    ));
                    return;
                }

                if (status.cancelled.get()) return;

                if (request.isAiFilterEnabled() && response.getAiInsights() == null) {
                    status.phase = "ai";
                    status.message = "Running AI analysis...";
                    status.progress = 70;

                    try {
                        String provider = request.resolvedAiProvider();
                        String modelName = request.aiModel();
                        log.info("AI analysis for {} — provider: {}, model: {}", profileName, provider, modelName);
                        AiInsightDto insights = aiAnalysisService.generateInsightsBatched(
                                response.getResources(), provider, modelName, status.cancelled,
                                (completed, total) -> {
                                    if (total > 1) {
                                        status.message = "AI analysis — batch " + (completed + 1) + "/" + total + "...";
                                        status.progress = Math.min(89, 70 + (int) ((completed / (double) Math.max(total, 1)) * 20));
                                    }
                                });
                        response.setAiInsights(insights);
                        response.setAiFiltering(AiFilteringDto.enabled(
                                provider,
                                response.getResources().size(),
                                insights.prioritizedActions().size(),
                                insights.model()
                        ));

                        // AI savings are available via insights.prioritizedActions() but
                        // we keep scanner-based potentialSavings as ground truth to stay
                        // consistent with idleResourcesCount (both derived from isActionable).

                        mapAiDetailsToResources(response.getResources(), insights);
                    } catch (Exception aiErr) {
                        log.warn("AI analysis failed for {}, saving report without AI insights: {}", profileName, aiErr.getMessage());
                        status.message = "AI analysis failed — saving report without AI insights";
                        String provider = request.resolvedAiProvider();
                        String modelName = request.aiModel();
                        response.setAiFiltering(AiFilteringDto.enabled(
                                provider != null ? provider : "unknown",
                                response.getResources().size(),
                                0,
                                modelName != null ? modelName : "unknown"
                        ));
                        response.setAiInsights(new AiInsightDto(
                                "AI analysis was attempted but failed: " + aiErr.getMessage(),
                                List.of(), List.of(), List.of(), List.of(), List.of(),
                                null, null, provider, modelName, null));
                    }
                }

                if (status.cancelled.get()) return;

                status.phase = "saving";
                status.message = "Saving report...";
                status.progress = 90;

                ScanReportDto report = reportService.saveReport(accountId, profileName, response);
                if (status.cancelled.get()) {
                    log.info("Job {} cancelled after save", jobId);
                    return;
                }
                status.report = report;
                status.phase = "complete";
                status.message = report.isPersisted()
                        ? "Analysis complete"
                        : "Analysis complete (report not saved to disk)";
                status.progress = 100;
                status.completedAt = System.currentTimeMillis();
                scanCompletedCounter.increment();
                scanDurationTimer.record(status.completedAt - startMs, TimeUnit.MILLISECONDS);
                resourcesFoundCounter.increment(response.getTotalResources());

                // Extract AI usage stats for audit
                var aiUsage = response.getAiInsights() != null ? response.getAiInsights().aiUsage() : null;
                if (aiUsage != null && aiUsage.totalTokens() != null) {
                    aiTokensUsedCounter.increment(aiUsage.totalTokens());
                }
                auditService.logScan(new AuditService.AuditEntry(
                        Instant.now(), accountId, profileName, "complete",
                        response.getTotalResources(), response.getTotalMonthlyCost(),
                        response.getActionableFindingsCount(), response.getPotentialSavings(),
                        request.isAiFilterEnabled(), request.resolvedAiProvider(),
                        response.getAnalyzedRegions() != null ? response.getAnalyzedRegions().size() : 0,
                        System.currentTimeMillis() - startMs,
                        aiUsage != null ? aiUsage.model() : null,
                        aiUsage != null ? aiUsage.promptTokens() : null,
                        aiUsage != null ? aiUsage.completionTokens() : null,
                        aiUsage != null ? aiUsage.totalTokens() : null,
                        aiUsage != null ? aiUsage.durationMs() : 0,
                        response.getScanCategory(),
                        response.getCostFindingsCount(),
                        response.getSecurityFindingsCount(),
                        response.getGovernanceFindingsCount()
                ));

            } catch (Exception e) {
                log.error("Analysis job failed: {}", e.getMessage(), e);
                status.phase = "error";
                status.message = e.getMessage();
                status.progress = 100;
                status.completedAt = System.currentTimeMillis();
                scanFailedCounter.increment();

                auditService.logScan(new AuditService.AuditEntry(
                        Instant.now(), accountId, profileName, "error",
                        0, 0, 0, 0,
                        request.isAiFilterEnabled(), request.resolvedAiProvider(),
                        0, System.currentTimeMillis() - startMs,
                        null, null, null, null, 0,
                        request.scanCategory(), 0, 0, 0
                ));
            }
        });

        return jobId;
    }

    /**
     * Retrieves the current status of a job by its ID.
     *
     * @param jobId the job identifier returned by {@link #submit}
     * @return the job status, or {@code null} if no job with the given ID exists
     */
    public JobStatus getStatus(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Immediately removes a job from the tracking map, regardless of its state.
     *
     * @param jobId the job identifier to remove
     */
    public void cleanup(String jobId) {
        jobs.remove(jobId);
    }

    /**
     * Returns all jobs that are not in a terminal state (complete, error, or cancelled).
     *
     * @return an unmodifiable list of active job statuses
     */
    public List<JobStatus> getActiveJobs() {
        return jobs.values().stream()
                .filter(j -> !"complete".equals(j.phase) && !"error".equals(j.phase) && !"cancelled".equals(j.phase))
                .toList();
    }

    /**
     * Returns a snapshot of all tracked jobs, including those in terminal states.
     *
     * @return an unmodifiable copy of all job statuses
     */
    public List<JobStatus> getAllJobs() {
        return List.copyOf(jobs.values());
    }

    /**
     * Checks whether the maximum number of concurrent scans is already running.
     *
     * @return {@code true} if the number of active jobs equals or exceeds {@link #MAX_CONCURRENT_SCANS}
     */
    public boolean isAtCapacity() {
        return getActiveJobs().size() >= MAX_CONCURRENT_SCANS;
    }

    /**
     * Returns the configured maximum number of scans that can execute simultaneously.
     *
     * @return the concurrency limit (currently 1)
     */
    public int maxConcurrentJobs() {
        return MAX_CONCURRENT_SCANS;
    }

    /**
     * Cancels a single job by its ID.
     *
     * <p>Sets the job's cancelled flag, updates its phase to {@code "cancelled"}, and
     * interrupts the underlying thread via {@link Future#cancel(boolean)}. Jobs that are
     * already in a terminal state cannot be cancelled.</p>
     *
     * @param jobId the job identifier to cancel
     * @return {@code true} if the job was successfully cancelled; {@code false} if the job
     *         was not found or was already in a terminal state
     */
    public boolean cancel(String jobId) {
        JobStatus status = jobs.get(jobId);
        if (status == null) return false;
        if ("complete".equals(status.phase) || "error".equals(status.phase) || "cancelled".equals(status.phase)) {
            return false;
        }
        status.cancelled.set(true);
        status.phase = "cancelled";
        status.message = "Scan cancelled by user";
        status.progress = 100;
        status.completedAt = System.currentTimeMillis();
        if (status.future != null) {
            status.future.cancel(true); // interrupt blocked thread
        }
        log.info("Job {} cancelled", jobId);
        return true;
    }

    /**
     * Cancels all active and queued jobs and replaces the executor pool.
     *
     * <p>This is a more aggressive operation than individual cancellation. After signaling
     * all jobs via their cancelled flags, it replaces the entire executor pool to free any
     * threads stuck on blocking I/O (e.g., waiting for AWS API responses). The old executor
     * is given a 10-second grace period via {@link ExecutorService#awaitTermination} before
     * being forcefully shut down.</p>
     *
     * @return the number of jobs that were successfully cancelled
     */
    public synchronized int cancelAll() {
        int count = 0;
        for (JobStatus status : jobs.values()) {
            if (!"complete".equals(status.phase) && !"error".equals(status.phase) && !"cancelled".equals(status.phase)) {
                status.cancelled.set(true);
                status.phase = "cancelled";
                status.message = "Scan cancelled by user";
                status.progress = 100;
                status.completedAt = System.currentTimeMillis();
                if (status.future != null) {
                    status.future.cancel(true);
                }
                count++;
            }
        }
        // Replace the executor to free any threads stuck on blocking I/O
        ExecutorService old = executor;
        executor = Executors.newFixedThreadPool(MAX_CONCURRENT_SCANS);
        old.shutdown();
        try {
            if (!old.awaitTermination(10, TimeUnit.SECONDS)) {
                old.shutdownNow();
            }
        } catch (InterruptedException ie) {
            old.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Cancelled {} jobs, executor pool replaced", count);
        return count;
    }

    /**
     * Removes all finished jobs (complete, error, or cancelled) from memory immediately,
     * regardless of their age.
     *
     * <p>Unlike {@link #evictStaleJobs()}, this does not wait for the retention period to elapse.</p>
     *
     * @return the number of jobs removed
     */
    public int clearFinished() {
        int before = jobs.size();
        jobs.entrySet().removeIf(e -> {
            String phase = e.getValue().phase;
            return "complete".equals(phase) || "error".equals(phase) || "cancelled".equals(phase);
        });
        return before - jobs.size();
    }

    /**
     * Maps AI-generated action items and right-sizing suggestions back to individual resources.
     *
     * <p>For each resource, this method looks up corresponding AI insights by resource ID and
     * populates the resource's {@code recommendationDetail} field with the AI's reasoning,
     * estimated savings, and/or downsizing suggestions. If the resource already has a
     * recommendation detail from the correlation engine, the AI detail is prepended
     * (separated by {@code " | "}) to preserve both sources of insight.</p>
     *
     * <p>If the AI did not produce any specific insight for a resource, the existing correlation
     * engine detail is left untouched.</p>
     *
     * @param resources the list of scanned resources to enrich
     * @param insights  the AI-generated insights containing actions and right-sizing suggestions
     */
    private void mapAiDetailsToResources(List<com.cloudsentinel.dto.ResourceDto> resources, AiInsightDto insights) {
        // Build lookup maps from AI insights
        var actionsByResourceId = new java.util.HashMap<String, AiInsightDto.ActionItem>();
        for (var action : insights.prioritizedActions()) {
            actionsByResourceId.put(action.resourceId(), action);
        }

        var rightSizingByResourceId = new java.util.HashMap<String, AiInsightDto.RightSizingSuggestion>();
        for (var rs : insights.rightSizing()) {
            rightSizingByResourceId.put(rs.resourceId(), rs);
        }

        for (var resource : resources) {
            var detail = new StringBuilder();

            var action = actionsByResourceId.get(resource.getResourceId());
            if (action != null) {
                detail.append(action.reasoning());
                if (action.estimatedSavings() > 0) {
                    detail.append(String.format(" | Potential savings: $%.2f/mo", action.estimatedSavings()));
                }
            }

            var rs = rightSizingByResourceId.get(resource.getResourceId());
            if (rs != null) {
                if (!detail.isEmpty()) detail.append(" | ");
                detail.append(String.format("Downsize: %s -> %s ($%.2f -> $%.2f/mo). %s",
                        rs.currentType(), rs.recommendedType(),
                        rs.currentCost(), rs.projectedCost(),
                        rs.reasoning()));
            }

            if (!detail.isEmpty()) {
                // AI provided specific insight — prepend to any existing correlation detail
                String existing = resource.getRecommendationDetail();
                if (existing != null && !existing.isBlank()) {
                    resource.setRecommendationDetail(detail + " | " + existing);
                } else {
                    resource.setRecommendationDetail(detail.toString());
                }
            }
            // If AI had nothing specific, keep whatever the correlation engine set — don't overwrite with generic fallback
        }
    }

    /**
     * Represents the real-time status of an analysis job.
     *
     * <p>Instances are created on submission and updated throughout the job lifecycle.
     * All mutable fields are {@code volatile} to ensure visibility across the submitting
     * thread, the executor thread running the job, and any polling threads reading status.</p>
     *
     * <p>Field descriptions:</p>
     * <ul>
     *   <li>{@code jobId} — unique identifier in the format {@code accountId_epochMillis}.</li>
     *   <li>{@code accountId} — the resolved AWS account ID for this scan.</li>
     *   <li>{@code profileName} — the AWS profile name used for display purposes.</li>
     *   <li>{@code phase} — current lifecycle phase: queued, scanning, ai, saving, complete, error, cancelled.</li>
     *   <li>{@code message} — human-readable status message suitable for UI display.</li>
     *   <li>{@code progress} — integer 0-100 representing overall completion percentage.</li>
     *   <li>{@code report} — the final {@link ScanReportDto}, populated only upon successful completion.</li>
     *   <li>{@code cancelled} — atomic flag used to signal cooperative cancellation to the running task.</li>
     *   <li>{@code completedAt} — epoch millis when the job reached a terminal state; 0 if still running.</li>
     *   <li>{@code future} — the {@link Future} handle for the submitted task, used for interruption on cancel.</li>
     * </ul>
     */
    public static class JobStatus {
        /** Unique job identifier in the format {@code accountId_epochMillis}. */
        public final String jobId;
        /** The resolved AWS account ID for deduplication and report association. */
        public final String accountId;
        /** The AWS profile name, used for display and audit logging. */
        public final String profileName;
        /** Current lifecycle phase: queued, scanning, ai, saving, complete, error, or cancelled. */
        public volatile String phase = "queued";
        /** Human-readable status message for UI display. */
        public volatile String message = "Queued...";
        /** Overall completion percentage (0-100). */
        public volatile int progress = 0;
        /** The final scan report, populated only on successful completion. */
        public volatile ScanReportDto report;
        /** Cooperative cancellation flag checked by the running task at multiple checkpoints. */
        public final AtomicBoolean cancelled = new AtomicBoolean(false);
        /** Epoch millis when the job reached a terminal state; 0 while running. */
        public volatile long completedAt = 0;
        /** Handle to the submitted task for interruption support on cancellation. */
        public volatile Future<?> future;

        /**
         * Constructs a new job status in the initial {@code queued} phase.
         *
         * @param jobId       the unique job identifier
         * @param accountId   the resolved AWS account ID
         * @param profileName the AWS profile name
         */
        JobStatus(String jobId, String accountId, String profileName) {
            this.jobId = jobId;
            this.accountId = accountId;
            this.profileName = profileName;
        }
    }
}
