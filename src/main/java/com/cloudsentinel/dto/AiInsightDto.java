package com.cloudsentinel.dto;

import java.util.List;

/**
 * Data Transfer Object containing the complete set of AI-generated insights for a cloud resource scan.
 *
 * <p>This is the primary container for all AI analysis output, produced when the AI model processes
 * the scanned resource data and generates a structured analysis. It includes an executive summary,
 * prioritized action items, right-sizing suggestions, architecture insights aligned with the AWS
 * Well-Architected Framework, and a phased cleanup plan with cost and risk estimates.</p>
 *
 * <p>The AI insight generation is resilient to failures: if the AI model is unavailable or returns
 * an unparseable response, the system falls back to an empty instance via {@link #empty()}, and the
 * scan report is still saved with resource data but without AI insights.</p>
 *
 * <p>This record is embedded within {@link AnalysisResponse} and is also persisted as part of
 * {@link ScanReportDto} for cached report retrieval and cross-report comparison.</p>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy. All nested
 * records follow the same convention.</p>
 *
 * <p>As a Java {@code record}, this class and all its nested records are immutable once constructed.</p>
 *
 * @param executiveSummary     a high-level summary of the scan findings suitable for executive audiences
 * @param prioritizedActions   a list of recommended actions ordered by priority/impact, each tied to a specific resource
 * @param rightSizing          suggestions for resizing over-provisioned resources to reduce costs
 * @param architectureInsights observations about the cloud architecture with improvement recommendations
 * @param wellArchitected      findings mapped to AWS Well-Architected Framework pillars
 * @param cleanupPlan          a phased plan for cleaning up idle resources, ordered by risk level
 * @param riskOverview         a narrative summary of risks associated with the recommended changes
 * @param costNarrative        a human-readable summary of current costs and potential savings
 * @param provider             the AI provider that generated these insights (e.g., "ollama" or "bedrock")
 * @param model                the specific AI model identifier used (e.g., "llama3.2", "claude-sonnet")
 * @param aiUsage              token usage and performance metrics for the AI call that produced these insights
 *
 * @see AnalysisResponse
 * @see AiUsageDto
 * @see AiFilteringDto
 */
public record AiInsightDto(
        String executiveSummary,
        List<ActionItem> prioritizedActions,
        List<RightSizingSuggestion> rightSizing,
        List<ArchitectureInsight> architectureInsights,
        List<WellArchitected> wellArchitected,
        List<CleanupPhase> cleanupPlan,
        String riskOverview,
        String costNarrative,
        String provider,
        String model,
        AiUsageDto aiUsage
) {
    /**
     * Represents a single prioritized action item recommended by the AI for a specific cloud resource.
     *
     * <p>Each action item identifies the target resource, describes what should be done, explains
     * the reasoning, assesses the risk, and estimates the monthly cost savings if the action is taken.</p>
     *
     * @param resourceId      the unique identifier of the target resource (e.g., an EC2 instance ID)
     * @param resourceType    the type of the resource (e.g., "EC2", "RDS", "EBS")
     * @param region          the AWS region where the resource resides
     * @param action          the recommended action to take (e.g., "Terminate", "Downsize", "Delete snapshot")
     * @param reasoning       the AI's explanation for why this action is recommended
     * @param risk            the assessed risk level of performing this action (e.g., "Low", "Medium", "High")
     * @param estimatedSavings the estimated monthly cost savings in USD if this action is taken
     */
    public record ActionItem(
            String resourceId,
            String resourceType,
            String region,
            String action,
            String reasoning,
            String risk,
            double estimatedSavings
    ) {}

    /**
     * Represents a right-sizing suggestion for an over-provisioned cloud resource.
     *
     * <p>The AI identifies resources that are larger than necessary for their workload and suggests
     * a smaller, more cost-effective instance or configuration type along with projected savings.</p>
     *
     * @param resourceId      the unique identifier of the resource to right-size
     * @param currentType     the current instance type or configuration (e.g., "m5.2xlarge")
     * @param recommendedType the recommended smaller instance type or configuration (e.g., "m5.large")
     * @param currentCost     the current monthly cost in USD
     * @param projectedCost   the projected monthly cost in USD after right-sizing
     * @param reasoning       the AI's explanation for this right-sizing suggestion
     */
    public record RightSizingSuggestion(
            String resourceId,
            String currentType,
            String recommendedType,
            double currentCost,
            double projectedCost,
            String reasoning
    ) {}

    /**
     * Represents an architecture-level insight identified by the AI during analysis.
     *
     * <p>These insights go beyond individual resource recommendations to identify broader
     * architectural patterns, anti-patterns, or opportunities for improvement.</p>
     *
     * @param category       the category of the insight (e.g., "Cost Optimization", "Reliability", "Performance")
     * @param finding        a description of the architectural finding or observation
     * @param recommendation the AI's recommended action or improvement
     */
    public record ArchitectureInsight(
            String category,
            String finding,
            String recommendation
    ) {}

    /**
     * Represents a finding aligned with the AWS Well-Architected Framework pillars.
     *
     * <p>The AI maps its observations to the six pillars of the Well-Architected Framework:
     * Operational Excellence, Security, Reliability, Performance Efficiency, Cost Optimization,
     * and Sustainability.</p>
     *
     * @param category the Well-Architected Framework pillar this finding relates to
     * @param finding  a summary of the finding
     * @param detail   additional detail or context for the finding
     */
    public record WellArchitected(
            String category,
            String finding,
            String detail
    ) {}

    /**
     * Represents a single phase in the AI-generated resource cleanup plan.
     *
     * <p>The cleanup plan is organized into sequential phases, typically ordered from lowest risk
     * (e.g., deleting unattached EBS volumes) to highest risk (e.g., terminating running instances).
     * Each phase specifies the actions to take, how many resources are affected, expected savings,
     * and the associated risk level.</p>
     *
     * @param phase             the phase name or label (e.g., "Phase 1: Quick Wins", "Phase 2: Moderate Changes")
     * @param actions           a description of the actions to perform in this phase
     * @param resourcesAffected the number of resources impacted by this phase
     * @param estimatedSavings  the estimated monthly cost savings in USD for this phase
     * @param riskLevel         the assessed risk level for this phase (e.g., "Low", "Medium", "High")
     */
    public record CleanupPhase(
            String phase,
            String actions,
            int resourcesAffected,
            double estimatedSavings,
            String riskLevel
    ) {}

    /**
     * Creates an empty {@code AiInsightDto} with no AI-generated content.
     *
     * <p>This factory method is used as a fallback when AI analysis is unavailable, fails, or
     * was not requested. All list fields are initialized to empty lists, and all string/object
     * fields are set to {@code null}. This ensures the scan report can still be constructed
     * and serialized without AI insights.</p>
     *
     * @return an {@code AiInsightDto} with all fields empty or {@code null}
     */
    public static AiInsightDto empty() {
        return new AiInsightDto(null, List.of(), List.of(), List.of(), List.of(), List.of(), null, null, null, null, null);
    }
}
