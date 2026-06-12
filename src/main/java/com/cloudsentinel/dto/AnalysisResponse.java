package com.cloudsentinel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Data Transfer Object representing the complete response from a cloud resource analysis scan.
 *
 * <p>This is the primary output payload returned by the analysis endpoints and serves as the central
 * container for all scan results. It aggregates resource-level data ({@link ResourceDto}), AI filtering
 * metadata ({@link AiFilteringDto}), AI-generated insights ({@link AiInsightDto}), scanner health
 * statistics, and cost summaries into a single cohesive response.</p>
 *
 * <p>The response lifecycle is as follows:
 * <ol>
 *   <li>The orchestrator populates resource data and scanner statistics during the scanning phase.</li>
 *   <li>If AI analysis is enabled, {@code aiFiltering} and {@code aiInsights} are populated after
 *       the AI model processes the scanned resources.</li>
 *   <li>The completed response is wrapped in a {@link ScanReportDto} for caching and persistence.</li>
 *   <li>It is serialized to JSON for API responses and rendered by Thymeleaf templates in the UI.</li>
 * </ol>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy, producing
 * field names such as {@code total_resources}, {@code total_monthly_cost}, {@code idle_resources_count},
 * {@code potential_savings}, {@code analyzed_regions}, {@code ai_filtering}, {@code ai_insights},
 * {@code scanner_success_count}, {@code scanner_failure_count}, {@code scanner_errors}, and
 * {@code credential_error}. The {@code @JsonIgnoreProperties(ignoreUnknown = true)} annotation
 * ensures forward compatibility when deserializing cached reports that may contain newer fields.</p>
 *
 * <p>This is a mutable JavaBean-style class (not a record) to support incremental population
 * during the multi-phase scan process.</p>
 *
 * @see ResourceDto
 * @see AiFilteringDto
 * @see AiInsightDto
 * @see ScanReportDto
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisResponse {

    private int totalResources;
    private double totalMonthlyCost;
    private int idleResourcesCount;
    private int actionableFindingsCount;
    private double potentialSavings;
    private List<ResourceDto> resources;
    private List<String> analyzedRegions;
    private String timestamp;
    private AiFilteringDto aiFiltering;
    private AiInsightDto aiInsights;
    private String scanCategory;
    private int scannerSuccessCount;
    private int scannerFailureCount;
    private List<String> scannerErrors;
    private String credentialError;
    private int costFindingsCount;
    private int securityFindingsCount;
    private int governanceFindingsCount;

    /**
     * Returns the total number of resources discovered during the scan.
     *
     * @return the total resource count across all scanned regions and resource types
     */
    public int getTotalResources() {
        return totalResources;
    }

    /**
     * Sets the total number of resources discovered during the scan.
     *
     * @param totalResources the total resource count to set
     */
    public void setTotalResources(int totalResources) {
        this.totalResources = totalResources;
    }

    /**
     * Returns the total estimated monthly cost in USD for all discovered resources.
     *
     * @return the aggregate monthly cost across all resources
     */
    public double getTotalMonthlyCost() {
        return totalMonthlyCost;
    }

    /**
     * Sets the total estimated monthly cost in USD for all discovered resources.
     *
     * @param totalMonthlyCost the aggregate monthly cost to set
     */
    public void setTotalMonthlyCost(double totalMonthlyCost) {
        this.totalMonthlyCost = totalMonthlyCost;
    }

    /**
     * Returns the number of resources identified as idle or underutilized.
     *
     * @return the count of idle resources
     * @deprecated Use {@link #getActionableFindingsCount()} instead. Kept for cached report compatibility.
     */
    public int getIdleResourcesCount() {
        return idleResourcesCount;
    }

    /**
     * Sets the number of resources identified as idle or underutilized.
     *
     * @param idleResourcesCount the idle resource count to set
     * @deprecated Use {@link #setActionableFindingsCount(int)} instead.
     */
    public void setIdleResourcesCount(int idleResourcesCount) {
        this.idleResourcesCount = idleResourcesCount;
    }

    /** Returns the total number of actionable findings across all finding types. */
    public int getActionableFindingsCount() { return actionableFindingsCount; }
    /** Sets the total number of actionable findings. Also sets idleResourcesCount for backward compatibility. */
    public void setActionableFindingsCount(int actionableFindingsCount) {
        this.actionableFindingsCount = actionableFindingsCount;
        this.idleResourcesCount = actionableFindingsCount;
    }

    /** Returns the number of cost optimization findings. */
    public int getCostFindingsCount() { return costFindingsCount; }
    public void setCostFindingsCount(int costFindingsCount) { this.costFindingsCount = costFindingsCount; }

    /** Returns the number of security findings. */
    public int getSecurityFindingsCount() { return securityFindingsCount; }
    public void setSecurityFindingsCount(int securityFindingsCount) { this.securityFindingsCount = securityFindingsCount; }

    /** Returns the number of governance findings. */
    public int getGovernanceFindingsCount() { return governanceFindingsCount; }
    public void setGovernanceFindingsCount(int governanceFindingsCount) { this.governanceFindingsCount = governanceFindingsCount; }

    /**
     * Returns the estimated potential monthly savings in USD if all idle resources are addressed.
     *
     * @return the potential monthly savings amount
     */
    public double getPotentialSavings() {
        return potentialSavings;
    }

    /**
     * Sets the estimated potential monthly savings in USD.
     *
     * @param potentialSavings the potential savings amount to set
     */
    public void setPotentialSavings(double potentialSavings) {
        this.potentialSavings = potentialSavings;
    }

    /**
     * Returns the list of all discovered cloud resources with their analysis details.
     *
     * @return the list of {@link ResourceDto} instances, one per discovered resource
     */
    public List<ResourceDto> getResources() {
        return resources;
    }

    /**
     * Sets the list of discovered cloud resources.
     *
     * @param resources the list of {@link ResourceDto} instances to set
     */
    public void setResources(List<ResourceDto> resources) {
        this.resources = resources;
    }

    /**
     * Returns the list of AWS region codes that were scanned during this analysis.
     *
     * @return the list of analyzed region codes (e.g., "us-east-1", "eu-west-1")
     */
    public List<String> getAnalyzedRegions() {
        return analyzedRegions;
    }

    /**
     * Sets the list of AWS region codes that were scanned.
     *
     * @param analyzedRegions the list of region codes to set
     */
    public void setAnalyzedRegions(List<String> analyzedRegions) {
        this.analyzedRegions = analyzedRegions;
    }

    /**
     * Returns the ISO-8601 timestamp indicating when this analysis was completed.
     *
     * @return the analysis completion timestamp as a string
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Sets the analysis completion timestamp.
     *
     * @param timestamp the ISO-8601 formatted timestamp string to set
     */
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * Returns the AI filtering metadata for this analysis.
     *
     * @return the {@link AiFilteringDto} describing whether AI filtering was applied and its results
     */
    public AiFilteringDto getAiFiltering() {
        return aiFiltering;
    }

    /**
     * Sets the AI filtering metadata for this analysis.
     *
     * @param aiFiltering the {@link AiFilteringDto} to set
     */
    public void setAiFiltering(AiFilteringDto aiFiltering) {
        this.aiFiltering = aiFiltering;
    }

    /**
     * Returns the AI-generated insights for this analysis, including executive summary,
     * prioritized actions, right-sizing suggestions, and cleanup plan.
     *
     * @return the {@link AiInsightDto} containing AI insights, or {@code null} if AI analysis was not performed
     */
    public AiInsightDto getAiInsights() {
        return aiInsights;
    }

    /**
     * Sets the AI-generated insights for this analysis.
     *
     * @param aiInsights the {@link AiInsightDto} to set
     */
    public void setAiInsights(AiInsightDto aiInsights) {
        this.aiInsights = aiInsights;
    }

    /**
     * Returns the number of resource scanners that completed successfully.
     *
     * @return the count of scanners that ran without errors
     */
    /** Returns the scan category used (COST_OPTIMIZATION, SECURITY_GOVERNANCE, or FULL). */
    public String getScanCategory() { return scanCategory; }
    /** Sets the scan category. */
    public void setScanCategory(String scanCategory) { this.scanCategory = scanCategory; }

    public int getScannerSuccessCount() { return scannerSuccessCount; }

    /**
     * Sets the number of resource scanners that completed successfully.
     *
     * @param scannerSuccessCount the success count to set
     */
    public void setScannerSuccessCount(int scannerSuccessCount) { this.scannerSuccessCount = scannerSuccessCount; }

    /**
     * Returns the number of resource scanners that failed during execution.
     *
     * @return the count of scanners that encountered errors
     */
    public int getScannerFailureCount() { return scannerFailureCount; }

    /**
     * Sets the number of resource scanners that failed during execution.
     *
     * @param scannerFailureCount the failure count to set
     */
    public void setScannerFailureCount(int scannerFailureCount) { this.scannerFailureCount = scannerFailureCount; }

    /**
     * Returns the list of error messages from scanners that failed during execution.
     *
     * <p>Each entry typically contains the scanner name and the error description, useful for
     * troubleshooting access-denied or configuration issues.</p>
     *
     * @return the list of scanner error messages, or {@code null} if no errors occurred
     */
    public List<String> getScannerErrors() { return scannerErrors; }

    /**
     * Sets the list of error messages from failed scanners.
     *
     * @param scannerErrors the list of error messages to set
     */
    public void setScannerErrors(List<String> scannerErrors) { this.scannerErrors = scannerErrors; }

    /**
     * Returns the credential-level error message, if the scan failed due to authentication issues.
     *
     * <p>This is set when the AWS credentials are invalid, expired, or lack sufficient permissions
     * to perform the scan. When this field is non-null, the scan was aborted early and
     * resource-level data may be empty or incomplete.</p>
     *
     * @return the credential error message, or {@code null} if credentials were valid
     */
    public String getCredentialError() { return credentialError; }

    /**
     * Sets the credential-level error message.
     *
     * @param credentialError the error message to set, or {@code null} if credentials were valid
     */
    public void setCredentialError(String credentialError) { this.credentialError = credentialError; }
}
