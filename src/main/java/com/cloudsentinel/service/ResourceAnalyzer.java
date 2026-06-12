package com.cloudsentinel.service;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.AiFilteringDto;
import com.cloudsentinel.dto.AiInsightDto;
import com.cloudsentinel.dto.AnalysisRequest;
import com.cloudsentinel.dto.AnalysisResponse;
import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.Severity;
import com.cloudsentinel.service.scanner.ResourceScanner;
import com.cloudsentinel.service.scanner.ResourceScanner.ScanCategory;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * Core orchestrator that drives the full AWS resource scanning pipeline.
 *
 * <p>This service coordinates the execution of all {@link ResourceScanner} implementations
 * (currently 50) across multiple AWS regions in parallel using Java 21 virtual threads.
 * Each scanner+region combination is submitted as a separate virtual thread task, enabling
 * thousands of concurrent API calls without exhausting OS threads.</p>
 *
 * <h3>Scan Flow</h3>
 * <ol>
 *   <li><strong>Credential validation</strong> — calls STS {@code GetCallerIdentity} to fail fast
 *       before launching scanner tasks.</li>
 *   <li><strong>Scanner filtering</strong> — selects scanners matching the requested
 *       {@link ScanCategory} (Cost &amp; Idle, Security &amp; Governance, or Full).</li>
 *   <li><strong>Parallel execution</strong> — each scanner+region pair runs as a virtual thread.
 *       Global scanners (IAM, Route53, etc.) run once regardless of selected regions.</li>
 *   <li><strong>Retry logic</strong> — up to 3 attempts for transient failures (timeouts,
 *       throttling, {@code TooManyRequests}, {@code ServiceUnavailable}). Credential errors
 *       and {@code AccessDenied} are permanent and never retried.</li>
 *   <li><strong>Deduplication</strong> — after all attempts, resources are deduplicated using a
 *       3-part composite key: {@code region::resourceType::resourceId}. This prevents duplicate
 *       entries when a scanner partially succeeds before timing out and is then retried.</li>
 *   <li><strong>Post-processing</strong> — reservation overlay and cross-resource correlation
 *       enrich the results.</li>
 *   <li><strong>Savings cap</strong> — potential savings are capped at total cost to prevent
 *       illogical results where savings exceed the overall spend.</li>
 * </ol>
 *
 * <h3>Actionability</h3>
 * The static {@link #isActionable(ResourceDto)} method centralizes the definition of what
 * constitutes an idle or actionable resource by matching recommendation string prefixes.
 * This single point of truth prevents filter-mismatch bugs between the dashboard counts
 * and the savings calculations.
 *
 * @see ResourceScanner
 * @see ReservationDetector
 * @see ResourceCorrelationEngine
 */
@Service
public class ResourceAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ResourceAnalyzer.class);

    private final AwsRegionService regionService;
    private final ReservationDetector reservationDetector;
    private final ResourceCorrelationEngine correlationEngine;
    private final List<ResourceScanner> scanners;

    /**
     * Constructs the resource analyzer with all required collaborators.
     *
     * <p>Spring auto-wires all {@link ResourceScanner} implementations into the {@code scanners}
     * list. On construction, the loaded scanner names are logged for operational visibility.</p>
     *
     * @param regionService      service for discovering available AWS regions
     * @param reservationDetector detector that overlays Reserved Instance and Savings Plan coverage
     * @param correlationEngine   engine that cross-references resources post-scan to enrich recommendations
     * @param scanners            all registered {@link ResourceScanner} beans (auto-collected by Spring)
     */
    private final boolean mockMode;

    public ResourceAnalyzer(AwsRegionService regionService,
                            ReservationDetector reservationDetector,
                            ResourceCorrelationEngine correlationEngine,
                            List<ResourceScanner> scanners,
                            org.springframework.core.env.Environment environment) {
        this.regionService = regionService;
        this.reservationDetector = reservationDetector;
        this.correlationEngine = correlationEngine;
        this.scanners = scanners;
        this.mockMode = List.of(environment.getActiveProfiles()).contains("mock-data");
        if (mockMode) {
            log.info("MOCK DATA MODE — AWS credentials not required. Generating demo data.");
        } else {
            log.info("Loaded {} resource scanners: {}", scanners.size(),
                    scanners.stream().map(s -> s.getClass().getSimpleName()).toList());
        }
    }

    /**
     * Builds an appropriate {@link AwsCredentialsProvider} from the analysis request.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>Explicit credentials (access key + secret key, optionally with session token) take
     *       highest priority. When present, the {@code profileName} field is treated purely as a
     *       display label for the scan.</li>
     *   <li>Named AWS profile from {@code ~/.aws/credentials} or {@code ~/.aws/config}.</li>
     *   <li>If neither is provided, an {@link IllegalArgumentException} is thrown. Falling back to
     *       the host's default credential chain is intentionally disallowed to prevent accidental
     *       scanning of unintended accounts.</li>
     * </ol>
     *
     * @param request the analysis request containing credentials or a profile name
     * @return a credentials provider suitable for building AWS SDK clients
     * @throws IllegalArgumentException if neither explicit credentials nor a profile name is provided
     */
    public AwsCredentialsProvider buildCredentialsProvider(AnalysisRequest request) {
        // Explicit credentials take priority — profileName may just be a scan label
        if (request.credentials() != null) {
            if (request.credentials().sessionToken() != null && !request.credentials().sessionToken().isBlank()) {
                return StaticCredentialsProvider.create(
                        AwsSessionCredentials.create(
                                request.credentials().accessKeyId(),
                                request.credentials().secretAccessKey(),
                                request.credentials().sessionToken()
                        )
                );
            }
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(
                            request.credentials().accessKeyId(),
                            request.credentials().secretAccessKey()
                    )
            );
        }
        if (request.profileName() != null && !request.profileName().isBlank()) {
            return ProfileCredentialsProvider.builder()
                    .profileName(request.profileName())
                    .build();
        }
        throw new IllegalArgumentException("Either a profile name or explicit credentials must be provided. Falling back to host identity is not allowed.");
    }

    /**
     * Callback interface for receiving scan progress updates during resource analysis.
     *
     * <p>Implementations receive two types of notifications:</p>
     * <ul>
     *   <li>{@link #onProgress(int, int)} — fired after each scanner+region task completes
     *       (first attempt only), providing numeric progress for progress bars.</li>
     *   <li>{@link #onStatus(String)} — fired for human-readable status messages such as
     *       retry notifications and post-scan phase changes.</li>
     * </ul>
     *
     * <p>Used by {@link AnalysisJobService} to update {@code JobStatus.progress} and
     * {@code JobStatus.message} fields for real-time UI updates.</p>
     */
    @FunctionalInterface
    public interface ScanProgressCallback {
        /**
         * Called when a scanner+region task completes (success or failure).
         *
         * @param completedTasks number of tasks completed so far
         * @param totalTasks     total number of scanner+region tasks
         */
        void onProgress(int completedTasks, int totalTasks);

        /**
         * Called with a human-readable status message for display in the UI.
         *
         * @param message descriptive status text (e.g., "Retrying 3 failed scanners (attempt 2/3)")
         */
        default void onStatus(String message) {}
    }

    /**
     * Convenience overload that runs analysis without cancellation support or progress reporting.
     *
     * @param request the analysis request specifying profile, regions, and scan category
     * @return the completed analysis response with all scanned resources and summary metrics
     */
    public AnalysisResponse analyzeAllResources(AnalysisRequest request) {
        return analyzeAllResources(request, new AtomicBoolean(false), null);
    }

    /**
     * Convenience overload that runs analysis with cancellation support but no progress reporting.
     *
     * @param request   the analysis request specifying profile, regions, and scan category
     * @param cancelled atomic flag that, when set to {@code true}, aborts the scan at the next check point
     * @return the completed (or partially completed) analysis response
     */
    public AnalysisResponse analyzeAllResources(AnalysisRequest request, AtomicBoolean cancelled) {
        return analyzeAllResources(request, cancelled, null);
    }

    /**
     * Executes the full resource scanning pipeline across all requested regions and scanner types.
     *
     * <p>This is the primary entry point for a complete scan. The method:</p>
     * <ol>
     *   <li>Validates credentials via STS {@code GetCallerIdentity}.</li>
     *   <li>Filters scanners by the requested {@link ScanCategory}.</li>
     *   <li>Builds scanner+region task pairs (global scanners produce a single task).</li>
     *   <li>Executes all tasks in parallel via virtual threads with a 60-second per-task timeout.</li>
     *   <li>Retries transient failures up to 3 times; permanent failures are recorded immediately.</li>
     *   <li>Deduplicates results using the composite key {@code region::resourceType::resourceId}.</li>
     *   <li>Overlays reservation/Savings Plan coverage data.</li>
     *   <li>Runs the cross-resource correlation engine.</li>
     *   <li>Computes summary metrics (total cost, idle count, potential savings).</li>
     * </ol>
     *
     * <p>If the {@code cancelled} flag is set during execution, the method returns partial results
     * with a credential error message of "Scan cancelled".</p>
     *
     * @param request          the analysis request specifying profile, regions, and scan category
     * @param cancelled        atomic flag checked between phases; setting it to {@code true} aborts the scan
     * @param progressCallback optional callback for progress and status updates; may be {@code null}
     * @return the analysis response containing resources, metrics, and any scanner errors
     */
    public AnalysisResponse analyzeAllResources(AnalysisRequest request, AtomicBoolean cancelled, ScanProgressCallback progressCallback) {
        String profileName = request.profileName() != null ? request.profileName() : "";
        if (mockMode && isMockProfile(profileName)) {
            return buildMockResponse(request, progressCallback);
        }
        AwsCredentialsProvider creds = buildCredentialsProvider(request);

        // Pre-flight credential check — fail fast if credentials are invalid/expired
        validateCredentials(creds, request.credentials() != null);

        List<String> regions = (request.regions() != null && !request.regions().isEmpty())
                ? request.regions()
                : regionService.listRegions();

        List<ResourceDto> allResources = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicBoolean credentialFailure = new AtomicBoolean(false);
        Set<String> errors = Collections.synchronizedSet(new LinkedHashSet<>());

        List<ResourceScanner> activeScanners = filterScannersByCategory(request.scanCategory());
        log.info("Running {} scanners for category '{}'", activeScanners.size(),
                request.scanCategory() != null ? request.scanCategory() : "full");

        // Build a list of scanner+region pairs to execute
        record ScanUnit(ResourceScanner scanner, String scannerName, String region) {}
        List<ScanUnit> allUnits = new ArrayList<>();
        for (ResourceScanner scanner : activeScanners) {
            String scannerName = scanner.getClass().getSimpleName().replace("Scanner", "");
            if (scanner.isGlobal()) {
                allUnits.add(new ScanUnit(scanner, scannerName, scanner.globalRegion()));
            } else {
                for (String region : regions) {
                    allUnits.add(new ScanUnit(scanner, scannerName, region));
                }
            }
        }

        int totalTasks = allUnits.size();
        int maxRetries = 3;
        List<ScanUnit> pending = new ArrayList<>(allUnits);
        AtomicInteger completedTasks = new AtomicInteger(0);

        for (int attempt = 1; attempt <= maxRetries && !pending.isEmpty(); attempt++) {
            if (cancelled.get() || credentialFailure.get()) break;
            final int currentAttempt = attempt;

            List<ScanUnit> failedThisRound = Collections.synchronizedList(new ArrayList<>());
            // Track failure reasons per unit for this round
            Map<String, String> failReasons = new java.util.concurrent.ConcurrentHashMap<>();

            if (attempt > 1) {
                log.info("Retry attempt {}/{} for {} failed scanner tasks", attempt, maxRetries, pending.size());
                if (progressCallback != null) {
                    progressCallback.onStatus("Retrying " + pending.size() + " failed scanners (attempt " + attempt + "/" + maxRetries + ")");
                }
                // Clear previous errors for tasks being retried
                for (ScanUnit unit : pending) {
                    failureCount.decrementAndGet();
                }
            }

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                record ScanTask(Future<?> future, ScanUnit unit) {}
                List<ScanTask> tasks = new ArrayList<>();

                for (ScanUnit unit : pending) {
                    Future<?> scanFuture = executor.submit(() -> {
                        if (cancelled.get() || credentialFailure.get()) return;
                        try {
                            List<ResourceDto> scanned = unit.scanner().scan(creds, unit.region());
                            FindingType ft = unit.scanner().findingType();
                            scanned.forEach(r -> {
                                r.setFindingType(ft);
                                r.setSeverity(classifySeverity(r));
                            });
                            allResources.addAll(scanned);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                            String classified = classifyError(unit.scanner(), e);
                            if (classified.startsWith("CREDENTIAL_ERROR:")) credentialFailure.set(true);
                            failReasons.put(unit.scannerName() + "|" + unit.region(), classified);
                            failedThisRound.add(unit);
                            log.warn("Scanner {} failed in {} (attempt {}): {}", unit.scannerName(), unit.region(), currentAttempt, e.getMessage());
                        }
                    });
                    tasks.add(new ScanTask(scanFuture, unit));
                }

                for (ScanTask task : tasks) {
                    try {
                        task.future().get(60, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        task.future().cancel(true);
                        failureCount.incrementAndGet();
                        String key = task.unit().scannerName() + "|" + task.unit().region();
                        failReasons.put(key, "TIMEOUT: " + task.unit().scannerName() + " in " + task.unit().region() + " — timed out after 60s");
                        failedThisRound.add(task.unit());
                        log.warn("Scanner {} timed out in {} after 60s (attempt {})", task.unit().scannerName(), task.unit().region(), currentAttempt);
                    } catch (Exception e) {
                        log.warn("Scanner task execution failed", e);
                    }

                    // Only increment progress for tasks on their first attempt
                    if (attempt == 1) {
                        int done = completedTasks.incrementAndGet();
                        if (progressCallback != null) {
                            progressCallback.onProgress(done, totalTasks);
                        }
                    }
                }
            }

            // Split failures into retryable (timeouts, throttling) vs permanent (access denied, credentials)
            List<ScanUnit> retryable = new ArrayList<>();
            for (ScanUnit unit : failedThisRound) {
                String key = unit.scannerName() + "|" + unit.region();
                String reason = failReasons.getOrDefault(key, "");
                if (reason.startsWith("CREDENTIAL_ERROR:") || reason.startsWith("ACCESS_DENIED:")) {
                    // Permanent failure — record immediately, don't retry
                    errors.add(reason);
                } else if (reason.startsWith("TIMEOUT:") || reason.contains("throttl") || reason.contains("Rate exceeded")
                        || reason.contains("TooManyRequests") || reason.contains("ServiceUnavailable")) {
                    // Transient failure — retry
                    retryable.add(unit);
                } else if (attempt < maxRetries) {
                    // Unknown error — retry once more, then give up
                    retryable.add(unit);
                } else {
                    // Final attempt — record as error
                    errors.add(reason + " (failed after " + maxRetries + " attempts)");
                }
            }

            pending = retryable;
        }

        if (cancelled.get()) {
            // Return partial results without post-processing
            AnalysisResponse response = new AnalysisResponse();
            response.setCredentialError("Scan cancelled");
            return response;
        }

        // Deduplicate resources — retried scanners may have added results before timing out
        var seen = new java.util.LinkedHashSet<String>();
        allResources.removeIf(r -> {
            String key = (r.getRegion() != null ? r.getRegion() : "") + "::" +
                         (r.getResourceType() != null ? r.getResourceType() : "") + "::" +
                         (r.getResourceId() != null ? r.getResourceId() : "");
            return !seen.add(key);
        });

        if (progressCallback != null) {
            progressCallback.onStatus("Checking reservations and correlating resources...");
        }
        reservationDetector.overlayReservationData(allResources, creds, regions);
        correlationEngine.correlate(allResources);

        double totalCost = allResources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        long actionableCount = allResources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .count();
        // Potential savings only counts COST findings to prevent security/governance findings
        // with $0 cost from polluting the savings metric
        double potentialSavings = allResources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .filter(r -> r.getFindingType() == FindingType.COST)
                .mapToDouble(ResourceDto::getMonthlyCostUsd)
                .sum();
        if (potentialSavings > totalCost) {
            log.warn("Potential savings (${}) exceeds total cost (${}), capping to total cost",
                    String.format("%.2f", potentialSavings), String.format("%.2f", totalCost));
            potentialSavings = totalCost;
        }

        // Per-finding-type counts for API consumers
        long costFindingsCount = allResources.stream()
                .filter(r -> r.getFindingType() == FindingType.COST).count();
        long securityFindingsCount = allResources.stream()
                .filter(r -> r.getFindingType() == FindingType.SECURITY).count();
        long governanceFindingsCount = allResources.stream()
                .filter(r -> r.getFindingType() == FindingType.GOVERNANCE).count();

        AnalysisResponse response = new AnalysisResponse();
        response.setTotalResources(allResources.size());
        response.setTotalMonthlyCost(totalCost);
        response.setActionableFindingsCount((int) actionableCount);
        response.setPotentialSavings(potentialSavings);
        response.setResources(allResources);
        response.setAnalyzedRegions(regions);
        response.setTimestamp(Instant.now().toString());
        response.setAiFiltering(AiFilteringDto.disabled());
        response.setScanCategory(request.scanCategory() != null ? request.scanCategory() : "FULL");
        response.setCostFindingsCount((int) costFindingsCount);
        response.setSecurityFindingsCount((int) securityFindingsCount);
        response.setGovernanceFindingsCount((int) governanceFindingsCount);
        response.setScannerSuccessCount(successCount.get());
        response.setScannerFailureCount(failureCount.get());

        if (!errors.isEmpty()) {
            response.setScannerErrors(new ArrayList<>(errors));
            String credError = detectCredentialError(errors);
            if (credError != null) {
                response.setCredentialError(credError);
            }
        }

        return response;
    }

    /**
     * Determines whether a resource is considered idle, unused, or otherwise actionable for
     * cost savings based on its recommendation string.
     *
     * <p>This method serves as the single source of truth for actionability classification
     * across the entire application. Both the idle resource count on the dashboard and the
     * potential savings calculation use this method, ensuring they always agree.</p>
     *
     * <p>A resource is actionable if its recommendation starts with any of these prefixes:</p>
     * <ul>
     *   <li>{@code Idle} — resource is running but not doing useful work</li>
     *   <li>{@code Consider Terminating} — stopped or deeply idle resource</li>
     *   <li>{@code Delete} — orphaned or expired resource (unattached EBS, expired ACM cert)</li>
     *   <li>{@code Release} — unassociated Elastic IP or similar releasable resource</li>
     *   <li>{@code Unused} — resource with no recent activity (IAM user &gt; 90 days, etc.)</li>
     *   <li>{@code Empty} — empty S3 bucket, empty ECR repository</li>
     *   <li>{@code Inactive} — no recent access (S3, Secrets Manager)</li>
     *   <li>{@code Stopped} — explicitly stopped compute resource</li>
     * </ul>
     *
     * <p>Recommendations starting with {@code Review}, {@code Active}, {@code In Use},
     * {@code Low Utilization}, or {@code Moderate Utilization} are <em>not</em> considered
     * actionable.</p>
     *
     * @param r the resource to evaluate
     * @return {@code true} if the resource's recommendation indicates it is actionable for savings
     */
    public static boolean isActionable(ResourceDto r) {
        String rec = r.getRecommendation();
        if (rec == null) return false;
        // Cost-oriented prefixes
        if (rec.startsWith("Idle") || rec.startsWith("Consider Terminating")
                || rec.startsWith("Delete") || rec.startsWith("Release")
                || rec.startsWith("Unused") || rec.startsWith("Empty")
                || rec.startsWith("Inactive") || rec.startsWith("Stopped")) {
            return true;
        }
        // Security/governance prefixes
        return rec.startsWith("Rotate") || rec.startsWith("Enable")
                || rec.startsWith("Restrict") || rec.startsWith("Expired")
                || rec.startsWith("Exposed") || rec.startsWith("Missing")
                || rec.startsWith("Stale") || rec.startsWith("Misconfigured");
    }

    /**
     * Derives a {@link Severity} from the resource's recommendation and finding type.
     *
     * <p>For cost findings, severity is based on actionability and cost impact.
     * For security/governance findings, severity is based on the recommendation verb.</p>
     */
    public static Severity classifySeverity(ResourceDto r) {
        String rec = r.getRecommendation();
        if (rec == null || rec.startsWith("Active") || rec.startsWith("In Use")) {
            return Severity.INFO;
        }
        FindingType ft = r.getFindingType();
        if (ft == null) ft = FindingType.COST;

        return switch (ft) {
            case SECURITY -> classifySecuritySeverity(rec);
            case GOVERNANCE -> classifyGovernanceSeverity(rec);
            case COST -> classifyCostSeverity(rec, r.getMonthlyCostUsd());
        };
    }

    private static Severity classifySecuritySeverity(String rec) {
        if (rec.startsWith("Expired") || rec.startsWith("Exposed") || rec.startsWith("Misconfigured")) {
            return Severity.CRITICAL;
        }
        if (rec.startsWith("Enable") || rec.startsWith("Rotate")) {
            return Severity.HIGH;
        }
        if (rec.startsWith("Unused") || rec.startsWith("Stale") || rec.startsWith("Missing") || rec.startsWith("Delete")) {
            return Severity.MEDIUM;
        }
        return Severity.LOW;
    }

    private static Severity classifyGovernanceSeverity(String rec) {
        if (rec.startsWith("Exposed") || rec.startsWith("Misconfigured")) return Severity.HIGH;
        if (rec.startsWith("Missing") || rec.startsWith("Stale") || rec.startsWith("Unused")) return Severity.MEDIUM;
        return Severity.LOW;
    }

    private static Severity classifyCostSeverity(String rec, double cost) {
        if (rec.startsWith("Idle") || rec.startsWith("Consider Terminating")
                || rec.startsWith("Delete") || rec.startsWith("Release")
                || rec.startsWith("Unused") || rec.startsWith("Empty")) {
            return cost > 50 ? Severity.HIGH : cost > 10 ? Severity.MEDIUM : Severity.LOW;
        }
        if (rec.startsWith("Stopped") || rec.startsWith("Inactive")) return Severity.MEDIUM;
        if (rec.startsWith("Low Utilization")) return Severity.LOW;
        return Severity.INFO;
    }

    /**
     * Filters the full list of scanners to only those matching the requested scan category.
     *
     * <p>If the category is {@code null}, blank, or {@code "full"}, all scanners are returned.
     * If the category string does not match any {@link ScanCategory} enum value, a warning is
     * logged and all scanners are returned as a safe fallback.</p>
     *
     * @param categoryName the scan category name (e.g., {@code "COST_IDLE"}, {@code "SECURITY_GOVERNANCE"},
     *                     {@code "full"}, or {@code null})
     * @return the filtered (or full) list of scanners
     */
    /**
     * Returns true if the profile name indicates a demo/mock profile.
     * In mock-data mode, only these profiles get mock data; real profiles still go through
     * the normal scan flow (and will fail-fast with credential errors if SSO is expired).
     */
    private static boolean isMockProfile(String profileName) {
        if (profileName == null) return false;
        String lower = profileName.toLowerCase();
        return lower.startsWith("demo") || lower.startsWith("mock");
    }

    /**
     * Builds a complete AnalysisResponse from mock data without any AWS API calls.
     * Used when the {@code mock-data} Spring profile is active.
     */
    private AnalysisResponse buildMockResponse(AnalysisRequest request, ScanProgressCallback progressCallback) {
        log.info("Generating mock data for demo (category: {})", request.scanCategory());
        if (progressCallback != null) progressCallback.onProgress(1, 3);

        List<String> regions = (request.regions() != null && !request.regions().isEmpty())
                ? request.regions() : List.of("us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-southeast-1");

        List<ResourceDto> allResources = MockDataGenerator.generate(regions, request.scanCategory());
        if (progressCallback != null) progressCallback.onProgress(2, 3);

        // Run correlation engine on mock data — this exercises real correlation rules
        correlationEngine.correlate(allResources);
        if (progressCallback != null) progressCallback.onProgress(3, 3);

        double totalCost = allResources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        long actionableCount = allResources.stream().filter(ResourceAnalyzer::isActionable).count();
        double potentialSavings = allResources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .filter(r -> r.getFindingType() == FindingType.COST)
                .mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        long costCount = allResources.stream().filter(r -> r.getFindingType() == FindingType.COST).count();
        long securityCount = allResources.stream().filter(r -> r.getFindingType() == FindingType.SECURITY).count();
        long governanceCount = allResources.stream().filter(r -> r.getFindingType() == FindingType.GOVERNANCE).count();

        AnalysisResponse response = new AnalysisResponse();
        response.setTotalResources(allResources.size());
        response.setTotalMonthlyCost(totalCost);
        response.setActionableFindingsCount((int) actionableCount);
        response.setPotentialSavings(potentialSavings);
        response.setResources(allResources);
        response.setAnalyzedRegions(regions);
        response.setTimestamp(Instant.now().toString());
        if (request.isAiFilterEnabled()) {
            AiInsightDto mockInsights = MockDataGenerator.generateMockAiInsights(allResources);
            response.setAiInsights(mockInsights);
            response.setAiFiltering(AiFilteringDto.enabled(
                    "demo", allResources.size(), (int) actionableCount, "mock-ai-v1 (pre-generated insights)"));
        } else {
            response.setAiFiltering(AiFilteringDto.disabled());
        }
        response.setScanCategory(request.scanCategory() != null ? request.scanCategory() : "FULL");
        response.setCostFindingsCount((int) costCount);
        response.setSecurityFindingsCount((int) securityCount);
        response.setGovernanceFindingsCount((int) governanceCount);
        response.setScannerSuccessCount(50);
        response.setScannerFailureCount(0);

        log.info("Mock data generated: {} resources, ${}, {} actionable, aiEnabled={}", allResources.size(), String.format("%.2f", totalCost), actionableCount, request.isAiFilterEnabled());
        return response;
    }

    private List<ResourceScanner> filterScannersByCategory(String categoryName) {
        if (categoryName == null || categoryName.isBlank() || "full".equalsIgnoreCase(categoryName)) {
            return scanners;
        }
        try {
            ScanCategory target = ScanCategory.valueOf(categoryName.toUpperCase());
            return scanners.stream()
                    .filter(s -> s.category() == target)
                    .toList();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown scan category '{}', running all scanners", categoryName);
            return scanners;
        }
    }

    /**
     * Classifies a scanner exception into a structured error category.
     *
     * <p>Error categories drive the retry logic:</p>
     * <ul>
     *   <li>{@code CREDENTIAL_ERROR:} — expired tokens, invalid grants, SSO issues. Sets the
     *       global {@code credentialFailure} flag to abort all remaining tasks.</li>
     *   <li>{@code ACCESS_DENIED:} — IAM permission issues for a specific scanner. Permanent failure.</li>
     *   <li>Unclassified — scanner name + error message. May be retried.</li>
     * </ul>
     *
     * @param scanner the scanner that threw the exception
     * @param e       the exception to classify
     * @return a classified error string with a category prefix
     */
    private String classifyError(ResourceScanner scanner, Exception e) {
        String msg = getRootMessage(e);
        String scannerName = scanner.getClass().getSimpleName().replace("Scanner", "");

        if (msg.contains("Unable to load credentials") || msg.contains("expired")
                || msg.contains("InvalidGrantException") || msg.contains("Token has expired")
                || msg.contains("SsoOidc") || msg.contains("security token")
                || msg.contains("not authorized") || msg.contains("InvalidIdentityToken")) {
            return "CREDENTIAL_ERROR: " + msg;
        }
        if (msg.contains("AccessDenied") || msg.contains("UnauthorizedAccess")) {
            return "ACCESS_DENIED: " + scannerName + " — " + msg;
        }
        return scannerName + " — " + msg;
    }

    /**
     * Scans the accumulated error set for credential-related failures and returns a
     * user-friendly error message if one is found.
     *
     * <p>Distinguishes between expired SSO sessions (which need {@code aws sso login})
     * and generic credential misconfigurations.</p>
     *
     * @param errors the set of classified error strings from all scanner tasks
     * @return a user-friendly credential error message, or {@code null} if no credential errors exist
     */
    private String detectCredentialError(Set<String> errors) {
        for (String error : errors) {
            if (error.startsWith("CREDENTIAL_ERROR:")) {
                if (error.contains("expired") || error.contains("InvalidGrant") || error.contains("SsoOidc")) {
                    return "AWS SSO session has expired. Run 'aws sso login' to refresh credentials.";
                }
                return "AWS credentials are invalid or missing. Check your profile configuration.";
            }
        }
        return null;
    }

    /**
     * Pre-flight credential validation via STS {@code GetCallerIdentity}.
     *
     * <p>Called before launching potentially 1000+ scanner tasks to fail fast with a clear,
     * actionable error message. Without this check, every scanner would independently fail
     * with cryptic SDK exceptions.</p>
     *
     * <p>Error messages are tailored based on whether the user provided manual credentials
     * (access key + secret) or is using a named AWS profile, since the remediation steps differ.</p>
     *
     * @param creds               the credentials provider to validate
     * @param isManualCredentials {@code true} if the user provided explicit access key/secret key;
     *                            {@code false} if using a named profile
     * @throws RuntimeException with a user-friendly message describing the credential issue and remediation
     */
    private void validateCredentials(AwsCredentialsProvider creds, boolean isManualCredentials) {
        try (var stsClient = ReadOnlyAwsClientFactory.build(
                software.amazon.awssdk.services.sts.StsClient.builder(),
                creds, software.amazon.awssdk.regions.Region.US_EAST_1)) {
            var identity = stsClient.getCallerIdentity();
            log.info("Credentials validated — Account: {}, ARN: {}", identity.account(), identity.arn());
        } catch (Exception e) {
            String msg = getRootMessage(e);
            log.debug("Credential validation failed: {}", msg);
            if (msg.contains("expired") || msg.contains("InvalidGrant") || msg.contains("SsoOidc")) {
                throw new RuntimeException(isManualCredentials
                        ? "The provided credentials have expired. Generate new temporary credentials and try again."
                        : "AWS SSO session has expired. Please run 'aws sso login --profile <your-profile>' to refresh credentials.");
            }
            if (msg.contains("security token") || msg.contains("Token")) {
                throw new RuntimeException(isManualCredentials
                        ? "Invalid security token. If your Access Key starts with ASIA, a valid Session Token is required."
                        : "AWS security token is invalid. Check your credentials or run 'aws sso login' to refresh.");
            }
            if (msg.contains("Unable to load credentials")) {
                throw new RuntimeException(isManualCredentials
                        ? "The provided credentials could not be validated. Check your Access Key, Secret Key, and Session Token."
                        : "AWS credentials are invalid or not found. Check your profile configuration.");
            }
            if (msg.contains("Profile file contained no credentials") || msg.contains("ProfileFile")) {
                // Extract just the profile name from the verbose SDK message
                String profile = "unknown";
                int idx = msg.indexOf("for profile '");
                if (idx >= 0) {
                    int end = msg.indexOf("'", idx + 13);
                    if (end > idx) profile = msg.substring(idx + 13, end);
                }
                throw new RuntimeException("No credentials found for profile '" + profile + "'. Check that the profile exists and has valid credentials configured.");
            }
            if (msg.contains("LoginProfile") || msg.contains("signin") || msg.contains("login_session") || msg.contains("class path")) {
                throw new RuntimeException("This profile uses an unsupported authentication method (login_session/signin). Use an SSO profile or enter credentials manually.");
            }
            // Truncate overly verbose SDK messages
            String cleanMsg = msg.length() > 200 ? msg.substring(0, 200) + "..." : msg;
            throw new RuntimeException("AWS credential check failed: " + cleanMsg);
        }
    }

    /**
     * Traverses the exception cause chain to extract the root cause message.
     *
     * <p>AWS SDK exceptions are often wrapped in multiple layers. This method walks to
     * the deepest cause and returns its message, or the class name if the message is null.</p>
     *
     * @param t the throwable to inspect
     * @return the root cause message, or the root cause class simple name if the message is null
     */
    private String getRootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
