package com.cloudsentinel.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object representing a single cloud resource discovered during an analysis scan.
 *
 * <p>Each instance captures the identity, location, state, cost, utilization metrics, and
 * optimization recommendations for one AWS resource. This DTO is the fundamental building block
 * of the analysis results: the {@link AnalysisResponse} contains a list of these, and each one
 * may optionally carry AI-generated analysis metadata via the embedded {@link AiAnalysisDto}.</p>
 *
 * <p>Resources span all 50 scanner types (EC2, RDS, EBS, S3, Lambda, DynamoDB, etc.) and are
 * populated by the respective {@code ResourceScanner} implementations. Cross-resource correlation
 * rules may further enrich the {@code recommendation} and {@code coveredBy} fields after all
 * scanners have completed.</p>
 *
 * <p>Key behavior notes:
 * <ul>
 *   <li>{@link #setMonthlyCostUsd(double)} applies a floor of 0.0 via {@code Math.max(0.0, value)}
 *       to prevent negative cost values from appearing in the results.</li>
 *   <li>The {@code aiAnalysis} field is annotated with {@code @JsonInclude(NON_NULL)} so it is
 *       omitted from JSON output when AI filtering was not applied.</li>
 *   <li>The {@code coveredBy} field is similarly annotated with {@code @JsonInclude(NON_NULL)} and
 *       is only populated when cross-resource correlation identifies that this resource is covered
 *       by another (e.g., a Reserved Instance or Savings Plan).</li>
 * </ul>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy. The
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} annotation ensures forward compatibility
 * when deserializing cached reports.</p>
 *
 * <p>This is a mutable JavaBean-style class to support incremental population by scanners
 * and post-processing by correlation rules.</p>
 *
 * @see AnalysisResponse
 * @see AiAnalysisDto
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceDto {

    private String region;
    private String resourceType;
    private String resourceId;
    private String resourceName;
    private String instanceType;
    private String state;
    private double monthlyCostUsd;
    private double cpuUtilizationAvg;
    private String recommendation;
    private String recommendationDetail;
    private String createdDate;

    private FindingType findingType = FindingType.COST;
    private Severity severity = Severity.INFO;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private java.util.Map<String, String> tags;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AiAnalysisDto aiAnalysis;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String coveredBy;

    /**
     * Returns the finding type classification for this resource.
     *
     * @return the {@link FindingType} (COST, SECURITY, or GOVERNANCE), never {@code null}
     */
    public FindingType getFindingType() {
        return findingType;
    }

    /**
     * Sets the finding type classification for this resource.
     *
     * @param findingType the finding type to set; if {@code null}, defaults to {@link FindingType#COST}
     */
    public void setFindingType(FindingType findingType) {
        this.findingType = findingType != null ? findingType : FindingType.COST;
    }

    /** Returns the severity level of this finding. */
    public Severity getSeverity() { return severity; }

    /** Sets the severity level. Null defaults to {@link Severity#INFO}. */
    public void setSeverity(Severity severity) {
        this.severity = severity != null ? severity : Severity.INFO;
    }

    /** Returns the AWS tags for this resource, or {@code null} if not collected. */
    public java.util.Map<String, String> getTags() { return tags; }

    /** Sets the AWS tags. Common keys: environment, cost_center, team, project. */
    public void setTags(java.util.Map<String, String> tags) { this.tags = tags; }

    /**
     * Returns the AWS region where this resource resides.
     *
     * @return the region code (e.g., "us-east-1", "eu-west-1")
     */
    public String getRegion() {
        return region;
    }

    /**
     * Sets the AWS region where this resource resides.
     *
     * @param region the region code to set
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Returns the type of this cloud resource.
     *
     * @return the resource type identifier (e.g., "EC2", "RDS", "EBS", "S3", "Lambda")
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Sets the type of this cloud resource.
     *
     * @param resourceType the resource type identifier to set
     */
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    /**
     * Returns the unique identifier of this resource within AWS.
     *
     * @return the resource ID (e.g., an EC2 instance ID, RDS cluster ARN, or S3 bucket name)
     */
    public String getResourceId() {
        return resourceId;
    }

    /**
     * Sets the unique identifier of this resource.
     *
     * @param resourceId the resource ID to set
     */
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    /**
     * Returns the human-readable name of this resource, typically derived from AWS tags.
     *
     * @return the resource name, or {@code null} if the resource has no Name tag
     */
    public String getResourceName() {
        return resourceName;
    }

    /**
     * Sets the human-readable name of this resource.
     *
     * @param resourceName the resource name to set
     */
    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }

    /**
     * Returns the instance type or configuration size of this resource, where applicable.
     *
     * @return the instance type (e.g., "m5.xlarge", "db.r5.large"), or {@code null} for resource
     *         types that do not have an instance type concept
     */
    public String getInstanceType() {
        return instanceType;
    }

    /**
     * Sets the instance type or configuration size of this resource.
     *
     * @param instanceType the instance type to set
     */
    public void setInstanceType(String instanceType) {
        this.instanceType = instanceType;
    }

    /**
     * Returns the current state or status of this resource.
     *
     * @return the resource state (e.g., "running", "stopped", "available", "idle")
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the current state or status of this resource.
     *
     * <p>The state is normalized to lowercase for consistent display across all resource types.
     * AWS APIs return states in varying formats (e.g., "ACTIVE", "available", "CREATE_COMPLETE",
     * "InService"), so normalization ensures uniform presentation in the dashboard and exports.
     * Underscores are replaced with hyphens for readability (e.g., "CREATE_COMPLETE" becomes
     * "create-complete").</p>
     *
     * @param state the resource state to set (will be lowercased and underscore-normalized)
     */
    public void setState(String state) {
        this.state = state != null ? state.toLowerCase().replace('_', '-') : null;
    }

    /**
     * Returns the estimated monthly cost of this resource in US dollars.
     *
     * @return the monthly cost in USD, always non-negative
     */
    public double getMonthlyCostUsd() {
        return monthlyCostUsd;
    }

    /**
     * Sets the estimated monthly cost of this resource in US dollars.
     *
     * <p>Applies a floor of {@code 0.0} using {@code Math.max(0.0, monthlyCostUsd)} to guard
     * against negative cost values that could arise from calculation errors or edge cases
     * in pricing data. This ensures that no resource ever displays a negative cost in the UI
     * or API responses.</p>
     *
     * @param monthlyCostUsd the monthly cost in USD to set; negative values are clamped to 0.0
     */
    public void setMonthlyCostUsd(double monthlyCostUsd) {
        this.monthlyCostUsd = Math.max(0.0, monthlyCostUsd);
    }

    /**
     * Returns the average CPU utilization percentage for this resource, where applicable.
     *
     * @return the average CPU utilization (0.0-100.0), or 0.0 for resource types without CPU metrics
     */
    public double getCpuUtilizationAvg() {
        return cpuUtilizationAvg;
    }

    /**
     * Sets the average CPU utilization percentage for this resource.
     *
     * @param cpuUtilizationAvg the average CPU utilization to set (0.0-100.0)
     */
    public void setCpuUtilizationAvg(double cpuUtilizationAvg) {
        this.cpuUtilizationAvg = cpuUtilizationAvg;
    }

    /**
     * Returns the optimization recommendation for this resource.
     *
     * <p>This is a short action label such as "Terminate", "Downsize", "Delete", "Review",
     * or "Consider removing". It may be set by the scanner, by cross-resource correlation rules,
     * or by AI analysis.</p>
     *
     * @return the recommendation label, or {@code null} if no recommendation applies
     */
    public String getRecommendation() {
        return recommendation;
    }

    /**
     * Sets the optimization recommendation for this resource.
     *
     * @param recommendation the recommendation label to set
     */
    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    /**
     * Returns the detailed explanation for the recommendation.
     *
     * <p>Provides additional context beyond the short recommendation label, such as
     * "Instance has been stopped for 30+ days" or "EBS volume has no attachments".</p>
     *
     * @return the detailed recommendation text, or {@code null} if no detail is available
     */
    public String getRecommendationDetail() {
        return recommendationDetail;
    }

    /**
     * Sets the detailed explanation for the recommendation.
     *
     * @param recommendationDetail the detailed recommendation text to set
     */
    public void setRecommendationDetail(String recommendationDetail) {
        this.recommendationDetail = recommendationDetail;
    }

    /**
     * Returns the creation date of this resource as a string.
     *
     * @return the creation date, or {@code null} if the creation date is unavailable
     */
    public String getCreatedDate() {
        return createdDate;
    }

    /**
     * Sets the creation date of this resource.
     *
     * @param createdDate the creation date string to set
     */
    public void setCreatedDate(String createdDate) {
        this.createdDate = createdDate;
    }

    /**
     * Returns the AI-generated analysis for this resource, if AI filtering was applied.
     *
     * <p>This field is only populated when the scan was configured with AI filtering enabled.
     * It contains the AI model's confidence level, reasoning, and provider information for
     * this specific resource.</p>
     *
     * @return the {@link AiAnalysisDto} with per-resource AI analysis, or {@code null} if AI
     *         filtering was not applied
     */
    public AiAnalysisDto getAiAnalysis() {
        return aiAnalysis;
    }

    /**
     * Sets the AI-generated analysis for this resource.
     *
     * @param aiAnalysis the {@link AiAnalysisDto} to set, or {@code null} to clear
     */
    public void setAiAnalysis(AiAnalysisDto aiAnalysis) {
        this.aiAnalysis = aiAnalysis;
    }

    /**
     * Returns the identifier of a covering resource, if this resource is covered by a reservation
     * or savings plan.
     *
     * <p>This field is populated by cross-resource correlation rules when they detect that this
     * resource's cost is offset by a Reserved Instance, Savings Plan, or similar commitment.
     * For example, an EC2 instance might have {@code coveredBy} set to "ri-xxxxxxxx" if it
     * matches an active reservation.</p>
     *
     * @return the covering resource identifier, or {@code null} if this resource is not covered
     */
    public String getCoveredBy() {
        return coveredBy;
    }

    /**
     * Sets the identifier of a covering resource (e.g., a Reserved Instance or Savings Plan).
     *
     * @param coveredBy the covering resource identifier to set, or {@code null} to clear
     */
    public void setCoveredBy(String coveredBy) {
        this.coveredBy = coveredBy;
    }
}
