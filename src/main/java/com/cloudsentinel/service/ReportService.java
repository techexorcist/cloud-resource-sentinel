package com.cloudsentinel.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.AnalysisResponse;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.ScanReportDto;
import com.cloudsentinel.dto.ScanReportDto.DiffSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Manages persistence, retrieval, pruning, and diffing of scan reports on the local filesystem.
 *
 * <p>Reports are stored as JSON files in {@code ~/.cloud-sentinel/reports/} with filenames
 * following the pattern {@code accountId_yyyy-MM-dd'T'HH-mm-ss.json}. Each AWS account
 * retains a maximum of 3 reports; older reports are automatically pruned after each save.</p>
 *
 * <h3>Atomic Writes</h3>
 * <p>Reports are written using a temp-file-then-atomic-move strategy. The JSON is first
 * serialized to a {@code .tmp} file, then moved atomically to its final name. This prevents
 * readers from seeing partially written JSON if a crash occurs during serialization.</p>
 *
 * <h3>Synchronized Operations</h3>
 * <p>{@link #saveReport} is {@code synchronized} to ensure that the save and subsequent
 * prune operations execute atomically. Without this, concurrent saves for the same account
 * could momentarily exceed the 3-report limit or produce inconsistent diff calculations.</p>
 *
 * <h3>Diff Computation</h3>
 * <p>When a previous report exists for the same account, a diff summary is computed comparing
 * resource counts, cost changes, and idle resource changes. Resources are compared using the
 * same 3-part composite key used for deduplication: {@code region::resourceType::resourceId}.</p>
 *
 * <h3>Account ID Resolution</h3>
 * <p>{@link #resolveAccountId(String)} extracts a 12-digit AWS account ID from profile names
 * that follow the convention {@code prefix-123456789012}. If no 12-digit suffix is found,
 * the profile name itself is used as the account identifier.</p>
 *
 * @see ScanReportDto
 * @see AnalysisResponse
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    /** Maximum number of reports retained per AWS account. */
    private static final int MAX_REPORTS_PER_ACCOUNT = 3;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private final Path reportsDir;
    private final ObjectMapper mapper;

    /**
     * Constructs the report service, creating the reports directory if it does not exist.
     *
     * <p>The reports directory is located at {@code ~/.cloud-sentinel/reports/}. The ObjectMapper
     * is configured with snake_case naming strategy and JavaTimeModule for {@link Instant}
     * serialization, matching the application-wide JSON contract.</p>
     */
    private final boolean mockMode;

    public ReportService(org.springframework.core.env.Environment environment) {
        this.reportsDir = Path.of(System.getProperty("user.home"), ".cloud-sentinel", "reports");
        this.mapper = new ObjectMapper();
        this.mapper.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.mockMode = List.of(environment.getActiveProfiles()).contains("mock-data");
        try {
            Files.createDirectories(reportsDir);
        } catch (IOException e) {
            log.error("Failed to create reports directory: {}", e.getMessage());
        }
        if (mockMode) {
            loadDemoReports();
        }
    }

    /**
     * Loads pre-baked demo reports from the classpath into the reports directory.
     * Uses fixed filenames so reports are overwritten on each restart or manual reload.
     */
    public void loadDemoReports() {
        String[][] demos = {
                {"demo/demo-report.json", "Demo_demo.json"},
                {"demo/demo-ai-report.json", "Demo-AI_demo.json"}
        };
        for (String[] entry : demos) {
            try (var in = getClass().getClassLoader().getResourceAsStream(entry[0])) {
                if (in == null) {
                    log.warn("Demo report not found on classpath: {}", entry[0]);
                    continue;
                }
                // Read, update scanned_at to now, write to reports dir
                var report = mapper.readValue(in, com.cloudsentinel.dto.ScanReportDto.class);
                report.setScannedAt(Instant.now());
                Path target = reportsDir.resolve(entry[1]);
                mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
                log.info("Loaded demo report: {} → {}", entry[0], target.getFileName());
            } catch (IOException e) {
                log.warn("Failed to load demo report {}: {}", entry[0], e.getMessage());
            }
        }
    }

    /**
     * Retrieves the most recent report for the given account, if one exists.
     *
     * @param accountId the AWS account ID (or profile name if account ID could not be resolved)
     * @return the latest report, or empty if no reports exist for this account
     */
    public Optional<ScanReportDto> getLatestReport(String accountId) {
        return getReportsForAccount(accountId).stream().findFirst();
    }

    /**
     * Checks whether a recent (non-stale) report exists for the given account.
     *
     * <p>Staleness is determined by {@link ScanReportDto#isStale()}, which uses a
     * configurable time threshold.</p>
     *
     * @param accountId the AWS account ID
     * @return {@code true} if a non-stale report exists
     */
    public boolean hasRecentReport(String accountId) {
        return getLatestReport(accountId).map(r -> !r.isStale()).orElse(false);
    }

    /**
     * Saves a scan report to disk with atomic write semantics and automatic pruning.
     *
     * <p>This method is {@code synchronized} to ensure the save-diff-prune sequence is atomic.
     * The write is performed via a temporary file that is atomically moved to its final location,
     * preventing readers from encountering partially written JSON.</p>
     *
     * <p>If a previous report exists for the account, a diff summary is computed and attached
     * to the new report before saving. After saving, old reports beyond the
     * {@link #MAX_REPORTS_PER_ACCOUNT} limit are pruned.</p>
     *
     * <p>If the write fails (e.g., disk full), the report is returned with
     * {@code persisted = false} so the caller can inform the user.</p>
     *
     * @param accountId   the AWS account ID
     * @param profileName the AWS profile name
     * @param response    the analysis response to persist
     * @return the saved report DTO (with diff summary if applicable)
     */
    public synchronized ScanReportDto saveReport(String accountId, String profileName, AnalysisResponse response) {
        ScanReportDto report = new ScanReportDto();
        report.setAccountId(accountId);
        report.setProfileName(profileName);
        report.setScannedAt(Instant.now());
        report.setAnalysisResponse(response);

        Optional<ScanReportDto> previous = getLatestReport(accountId);
        if (previous.isPresent()) {
            report.setDiffSummary(computeDiff(previous.get(), report));
        }

        String filename = accountId + "_" + FILE_TS.format(Instant.now()) + ".json";
        Path filePath = reportsDir.resolve(filename);

        try {
            // Write to temp file, then atomic move — prevents readers seeing partial JSON
            Path tempFile = reportsDir.resolve(filename + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), report);
            Files.move(tempFile, filePath, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            log.info("Report saved: {}", filePath.getFileName());
        } catch (IOException e) {
            log.error("Failed to save report: {}", e.getMessage());
            report.setPersisted(false);
        }

        pruneReports(accountId);
        return report;
    }

    /**
     * Retrieves all persisted reports for the given account, sorted by scan time (newest first).
     *
     * <p>Malformed report files are skipped with a warning log rather than failing the entire
     * retrieval operation.</p>
     *
     * @param accountId the AWS account ID
     * @return list of reports sorted by scan time descending; may be empty
     */
    public List<ScanReportDto> getReportsForAccount(String accountId) {
        List<ScanReportDto> reports = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, accountId + "_*.json")) {
            for (Path path : stream) {
                try {
                    reports.add(mapper.readValue(path.toFile(), ScanReportDto.class));
                } catch (IOException e) {
                    log.warn("Failed to read report {}: {}", path.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("No reports found for account {}", accountId);
        }
        reports.sort(Comparator.comparing(ScanReportDto::getScannedAt).reversed());
        return reports;
    }

    /**
     * Deletes the oldest reports for the given account, keeping only the most recent
     * {@link #MAX_REPORTS_PER_ACCOUNT} files.
     *
     * <p>Files are sorted by filename (which contains a timestamp) in reverse order,
     * and any files beyond the retention limit are deleted.</p>
     *
     * @param accountId the AWS account ID whose reports should be pruned
     */
    private void pruneReports(String accountId) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, accountId + "_*.json")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return;
        }
        files.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = MAX_REPORTS_PER_ACCOUNT; i < files.size(); i++) {
            try {
                Files.delete(files.get(i));
                log.info("Pruned old report: {}", files.get(i).getFileName());
            } catch (IOException e) {
                log.warn("Failed to prune report: {}", e.getMessage());
            }
        }
    }

    /**
     * Computes a diff summary between two reports for the same account.
     *
     * <p>Compares resources using the composite key {@code region::resourceType::resourceId}
     * to identify added and removed resources. Also calculates cost and idle resource count
     * changes. A human-readable narrative is generated summarizing the changes.</p>
     *
     * @param previous the earlier report to compare against
     * @param current  the newer report
     * @return the diff summary, or {@code null} if either report lacks an analysis response
     */
    private DiffSummary computeDiff(ScanReportDto previous, ScanReportDto current) {
        if (previous.getAnalysisResponse() == null || current.getAnalysisResponse() == null) return null;
        var prevResources = previous.getAnalysisResponse().getResources();
        var currResources = current.getAnalysisResponse().getResources();
        if (prevResources == null) prevResources = List.of();
        if (currResources == null) currResources = List.of();

        Set<String> prevIds = new HashSet<>();
        prevResources.forEach(r -> prevIds.add(resourceKey(r)));

        Set<String> currIds = new HashSet<>();
        currResources.forEach(r -> currIds.add(resourceKey(r)));

        List<String> added = currIds.stream().filter(id -> !prevIds.contains(id)).toList();
        List<String> removed = prevIds.stream().filter(id -> !currIds.contains(id)).toList();

        double prevCost = previous.getAnalysisResponse().getTotalMonthlyCost();
        double currCost = current.getAnalysisResponse().getTotalMonthlyCost();

        int prevIdle = previous.getAnalysisResponse().getIdleResourcesCount();
        int currIdle = current.getAnalysisResponse().getIdleResourcesCount();

        String narrative = buildDiffNarrative(added.size(), removed.size(), currCost - prevCost, currIdle - prevIdle);

        return new DiffSummary(
                added.size(),
                removed.size(),
                Math.round((currCost - prevCost) * 100.0) / 100.0,
                currIdle - prevIdle,
                added,
                removed,
                narrative
        );
    }

    /**
     * Builds a human-readable narrative summarizing the changes between two reports.
     *
     * @param added      number of newly detected resources
     * @param removed    number of resources no longer found
     * @param costChange monthly cost change in USD (positive = increase, negative = decrease)
     * @param idleChange change in idle resource count (positive = more idle)
     * @return a period-delimited narrative string, or a "no significant changes" message
     */
    private String buildDiffNarrative(int added, int removed, double costChange, int idleChange) {
        var parts = new ArrayList<String>();

        if (added > 0) parts.add(added + " new resource(s) detected");
        if (removed > 0) parts.add(removed + " resource(s) no longer found");
        if (Math.abs(costChange) > 0.01) {
            parts.add(String.format("monthly cost %s by $%.2f",
                    costChange > 0 ? "increased" : "decreased", Math.abs(costChange)));
        }
        if (idleChange != 0) {
            parts.add(String.format("idle resources %s by %d",
                    idleChange > 0 ? "increased" : "decreased", Math.abs(idleChange)));
        }

        if (parts.isEmpty()) return "No significant changes since last scan.";
        return String.join(". ", parts) + ".";
    }

    /**
     * Returns a map of account IDs to their respective report counts.
     *
     * <p>Parses account IDs from report filenames (everything before the first underscore).</p>
     *
     * @return a map of account ID to report count, ordered by insertion
     */
    public java.util.Map<String, Integer> getReportCountsByAccount() {
        var counts = new LinkedHashMap<String, Integer>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "*.json")) {
            for (Path path : stream) {
                String filename = path.getFileName().toString();
                // filename format: accountId_timestamp.json
                int firstUnderscore = filename.indexOf('_');
                if (firstUnderscore > 0) {
                    String accountId = filename.substring(0, firstUnderscore);
                    counts.merge(accountId, 1, Integer::sum);
                }
            }
        } catch (IOException e) {
            log.debug("Failed to count reports by account: {}", e.getMessage());
        }
        return counts;
    }

    /**
     * Returns the total number of persisted report files across all accounts.
     *
     * @return the total report count
     */
    public int getTotalReportCount() {
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "*.json")) {
            for (Path ignored : stream) {
                count++;
            }
        } catch (IOException e) {
            log.debug("Failed to count reports: {}", e.getMessage());
        }
        return count;
    }

    /**
     * Deletes all persisted report files across all accounts.
     *
     * @return the number of files successfully deleted
     */
    public int clearAllReports() {
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "*.json")) {
            for (Path path : stream) {
                try {
                    Files.delete(path);
                    deleted++;
                } catch (IOException e) {
                    log.warn("Failed to delete report {}: {}", path.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to clear reports: {}", e.getMessage());
        }
        log.info("Cleared {} cached reports", deleted);
        return deleted;
    }

    /**
     * Generates a composite key for a resource using the format {@code region::resourceType::resourceId}.
     *
     * <p>This key format matches the deduplication key used in {@link ResourceAnalyzer}.</p>
     *
     * @param r the resource DTO
     * @return the composite key string
     */
    private static String resourceKey(ResourceDto r) {
        return (r.getRegion() != null ? r.getRegion() : "") + "::" +
               (r.getResourceType() != null ? r.getResourceType() : "") + "::" +
               (r.getResourceId() != null ? r.getResourceId() : "");
    }

    /**
     * Resolves an AWS account ID from a profile name.
     *
     * <p>If the profile name ends with a hyphen-separated 12-digit number (e.g.,
     * {@code mycompany-production-123456789012}), that number is extracted as the account ID.
     * Otherwise, the profile name itself is returned as the identifier.</p>
     *
     * @param profileName the AWS profile name, or {@code null}/blank for the default
     * @return the resolved account ID or profile name
     */
    public String resolveAccountId(String profileName) {
        if (profileName == null || profileName.isBlank()) return "default";
        String[] parts = profileName.split("-");
        if (parts.length >= 2) {
            String lastPart = parts[parts.length - 1];
            if (lastPart.matches("\\d{12}")) return lastPart;
        }
        return profileName;
    }
}
