package com.cloudsentinel.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Data Transfer Object representing a persisted scan report for a specific AWS account.
 *
 * <p>This class wraps an {@link AnalysisResponse} with additional metadata needed for report
 * management: the AWS account ID, credential profile name, scan timestamp, persistence state,
 * and an optional diff summary comparing this report against a previous one.</p>
 *
 * <p>The system maintains up to 3 cached reports per AWS account, enabling users to view historical
 * scan results and compare reports over time via the {@code GET /analyse/compare} endpoint. The
 * {@link DiffSummary} nested record captures the delta between two reports when a comparison is
 * performed.</p>
 *
 * <p>Key behavior notes:
 * <ul>
 *   <li>{@link #isStale()} returns {@code true} if the report is older than 24 hours (86400 seconds),
 *       which the UI uses to display a staleness warning badge.</li>
 *   <li>The {@code persisted} flag defaults to {@code true} and is set to {@code false} for
 *       in-flight reports that have not yet been saved to disk.</li>
 *   <li>The {@code @JsonInclude(NON_NULL)} annotation suppresses null fields from serialized JSON,
 *       keeping responses compact when optional fields like {@code diffSummary} are absent.</li>
 * </ul>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy. The
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} annotation ensures forward compatibility.</p>
 *
 * <p>This is a mutable JavaBean-style class to support incremental population during the scan
 * and report-saving lifecycle.</p>
 *
 * @see AnalysisResponse
 * @see DiffSummary
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanReportDto {

    private String accountId;
    private String profileName;
    private Instant scannedAt;
    private AnalysisResponse analysisResponse;
    private DiffSummary diffSummary;
    private boolean persisted = true;

    /**
     * Returns the AWS account ID associated with this scan report.
     *
     * @return the 12-digit AWS account ID
     */
    public String getAccountId() { return accountId; }

    /**
     * Sets the AWS account ID associated with this scan report.
     *
     * @param accountId the AWS account ID to set
     */
    public void setAccountId(String accountId) { this.accountId = accountId; }

    /**
     * Returns the name of the AWS credential profile used for this scan.
     *
     * @return the profile name (e.g., "default", "production", "staging")
     */
    public String getProfileName() { return profileName; }

    /**
     * Sets the name of the AWS credential profile used for this scan.
     *
     * @param profileName the profile name to set
     */
    public void setProfileName(String profileName) { this.profileName = profileName; }

    /**
     * Returns the timestamp when this scan was performed.
     *
     * @return the scan timestamp as an {@link Instant}
     */
    public Instant getScannedAt() { return scannedAt; }

    /**
     * Sets the timestamp when this scan was performed.
     *
     * @param scannedAt the scan timestamp to set
     */
    public void setScannedAt(Instant scannedAt) { this.scannedAt = scannedAt; }

    /**
     * Returns the full analysis response containing all scan results, resources, and AI insights.
     *
     * @return the {@link AnalysisResponse} for this report
     */
    public AnalysisResponse getAnalysisResponse() { return analysisResponse; }

    /**
     * Sets the analysis response for this report.
     *
     * @param analysisResponse the {@link AnalysisResponse} to set
     */
    public void setAnalysisResponse(AnalysisResponse analysisResponse) { this.analysisResponse = analysisResponse; }

    /**
     * Returns the diff summary comparing this report against a previous report, if available.
     *
     * <p>This field is only populated when the {@code GET /analyse/compare} endpoint is used
     * to compare two reports, or when the system automatically computes a diff upon saving a
     * new report for an account that already has a previous report.</p>
     *
     * @return the {@link DiffSummary}, or {@code null} if no comparison has been performed
     */
    public DiffSummary getDiffSummary() { return diffSummary; }

    /**
     * Sets the diff summary for this report.
     *
     * @param diffSummary the {@link DiffSummary} to set, or {@code null} to clear
     */
    public void setDiffSummary(DiffSummary diffSummary) { this.diffSummary = diffSummary; }

    /**
     * Returns whether this report has been persisted to disk.
     *
     * @return {@code true} if the report has been saved, {@code false} if it is still in-flight
     */
    public boolean isPersisted() { return persisted; }

    /**
     * Sets the persistence state of this report.
     *
     * @param persisted {@code true} if the report has been saved to disk, {@code false} otherwise
     */
    public void setPersisted(boolean persisted) { this.persisted = persisted; }

    /**
     * Determines whether this scan report is stale (older than 24 hours).
     *
     * <p>A report is considered stale if its {@code scannedAt} timestamp is more than 86400 seconds
     * (24 hours) before the current time. The UI uses this to display a warning badge indicating
     * that the data may be outdated and a fresh scan is recommended.</p>
     *
     * <p>This method is annotated with {@code @JsonIgnore} so it is not included in serialized
     * JSON output; it is a computed property for UI/logic use only.</p>
     *
     * @return {@code true} if the report is older than 24 hours, {@code false} if it is recent
     *         or if {@code scannedAt} is {@code null}
     */
    @JsonIgnore
    public boolean isStale() {
        return scannedAt != null && scannedAt.isBefore(Instant.now().minusSeconds(86400));
    }

    /**
     * An immutable record representing the differences between two scan reports for the same AWS account.
     *
     * <p>This is produced by the report comparison logic when two {@link ScanReportDto} instances
     * are compared. It captures the delta in resource counts, cost changes, idle resource count
     * changes, and provides lists of newly discovered and removed resource IDs along with a
     * human-readable narrative summarizing the changes.</p>
     *
     * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy.</p>
     *
     * @param resourcesAdded    the number of resources present in the newer report but absent in the older one
     * @param resourcesRemoved  the number of resources present in the older report but absent in the newer one
     * @param costChange        the change in total monthly cost (positive means cost increased, negative means decreased)
     * @param idleCountChange   the change in the number of idle resources (positive means more idle resources)
     * @param newResources      list of resource IDs that are new in the newer report
     * @param removedResources  list of resource IDs that were removed since the older report
     * @param narrative         a human-readable summary of the changes between the two reports
     */
    public record DiffSummary(
            int resourcesAdded,
            int resourcesRemoved,
            double costChange,
            int idleCountChange,
            List<String> newResources,
            List<String> removedResources,
            String narrative
    ) {}
}
