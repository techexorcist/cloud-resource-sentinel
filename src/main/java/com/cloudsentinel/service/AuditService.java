package com.cloudsentinel.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Persists audit trail entries for every scan execution, including successful completions,
 * errors, cancellations, and credential failures.
 *
 * <p>Audit entries are stored as individual JSON files in {@code ~/.cloud-sentinel/audit/}
 * with filenames in the format {@code yyyy-MM-dd'T'HH-mm-ss_accountId.json}. Each entry
 * records the scan outcome, resource counts, cost totals, AI usage statistics, and duration.</p>
 *
 * <p>The audit log provides historical traceability for scan operations and enables
 * the scan history view in the UI. Entries are serialized using snake_case JSON to maintain
 * consistency with the application-wide API contract.</p>
 *
 * @see AnalysisJobService
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private final Path auditDir;
    private final ObjectMapper mapper;

    /**
     * Constructs the audit service, creating the audit directory at
     * {@code ~/.cloud-sentinel/audit/} if it does not exist.
     */
    private final int retentionDays;

    public AuditService(@Value("${cloud-sentinel.audit.retention-days:90}") int retentionDays) {
        this.retentionDays = retentionDays;
        this.auditDir = Path.of(System.getProperty("user.home"), ".cloud-sentinel", "audit");
        this.mapper = new ObjectMapper();
        this.mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Files.createDirectories(auditDir);
            pruneOldEntries();
        } catch (IOException e) {
            log.error("Failed to create audit directory: {}", e.getMessage());
        }
    }

    /** Deletes audit entries older than the configured retention period. Runs daily at 2am and on startup. */
    @Scheduled(cron = "0 0 2 * * *")
    public void pruneOldEntries() {
        try (var files = Files.list(auditDir)) {
            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            files.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("Pruned old audit entry: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("Failed to prune audit entry {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to scan audit directory for pruning: {}", e.getMessage());
        }
    }

    /**
     * Persists an audit entry to disk as a JSON file.
     *
     * <p>The filename is derived from the entry's timestamp and account ID for chronological
     * ordering and easy identification. Write failures are logged but do not propagate
     * exceptions to avoid disrupting the scan flow.</p>
     *
     * @param entry the audit entry to persist
     */
    public void logScan(AuditEntry entry) {
        String filename = TS.format(entry.timestamp()) + "_" + entry.accountId() + ".json";
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(auditDir.resolve(filename).toFile(), entry);
        } catch (IOException e) {
            log.error("Failed to write audit entry: {}", e.getMessage());
        }
    }

    /**
     * Retrieves the most recent audit entries, sorted by timestamp descending.
     *
     * <p>Malformed audit files are skipped with a debug log. If no entries exist,
     * an empty list is returned.</p>
     *
     * @param limit the maximum number of entries to return
     * @return the list of audit entries, ordered newest first, capped at {@code limit}
     */
    public List<AuditEntry> getHistory(int limit) {
        List<AuditEntry> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(auditDir, "*.json")) {
            for (Path path : stream) {
                try {
                    entries.add(mapper.readValue(path.toFile(), AuditEntry.class));
                } catch (IOException e) {
                    log.debug("Skipping audit file {}: {}", path.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("No audit entries found");
        }
        entries.sort(Comparator.comparing(AuditEntry::timestamp).reversed());
        return entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    /**
     * Immutable record representing a single scan audit entry.
     *
     * <p>Captures the complete context of a scan execution including timing, resource metrics,
     * AI configuration, and token usage statistics.</p>
     *
     * @param timestamp        when the scan completed (or failed/was cancelled)
     * @param accountId        the resolved AWS account ID
     * @param profileName      the AWS profile name used for the scan
     * @param status           the outcome: "complete", "error", "cancelled", or "credential_error"
     * @param resourceCount    total number of resources found
     * @param totalCost        total estimated monthly cost in USD
     * @param idleCount        number of actionable/idle resources
     * @param potentialSavings estimated monthly savings from addressing idle resources
     * @param aiEnabled        whether AI analysis was enabled for this scan
     * @param aiProvider       the AI provider used (e.g., "ollama", "bedrock"), or null
     * @param regionsScanned   number of AWS regions scanned
     * @param durationMs       total scan duration in milliseconds
     * @param aiModel          the specific AI model used, or null
     * @param promptTokens     AI prompt token count, or null if AI was not used
     * @param completionTokens AI completion token count, or null
     * @param totalTokens      AI total token count, or null
     * @param aiDurationMs     AI analysis duration in milliseconds
     */
    public record AuditEntry(
            Instant timestamp,
            String accountId,
            String profileName,
            String status,
            int resourceCount,
            double totalCost,
            int idleCount,
            double potentialSavings,
            boolean aiEnabled,
            String aiProvider,
            int regionsScanned,
            long durationMs,
            String aiModel,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            long aiDurationMs,
            String scanCategory,
            int costFindingsCount,
            int securityFindingsCount,
            int governanceFindingsCount
    ) {}
}
