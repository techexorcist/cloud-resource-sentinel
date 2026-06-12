package com.cloudsentinel.dto;

/**
 * Data Transfer Object representing the AI-generated analysis results for a single cloud resource.
 *
 * <p>This record is embedded within {@link ResourceDto} to carry per-resource AI analysis metadata,
 * including the AI model's confidence level in its assessment, the reasoning behind the recommendation,
 * and which AI provider produced the analysis. It is typically populated during the AI filtering phase
 * when the system asks an AI model to evaluate whether a candidate resource is truly idle or underutilized.</p>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy, so field names
 * appear as {@code ai_confidence}, {@code ai_reasoning}, and {@code ai_provider} in API responses.</p>
 *
 * <p>As a Java {@code record}, instances of this class are immutable once constructed. All fields
 * are set via the canonical constructor and accessed through their corresponding accessor methods.</p>
 *
 * @param aiConfidence  the AI model's confidence score (0-100) indicating how certain it is
 *                      that the resource is idle or underutilized
 * @param aiReasoning   a human-readable explanation from the AI model describing why the resource
 *                      was classified as idle, underutilized, or active
 * @param aiProvider    the name of the AI provider that generated this analysis (e.g., "ollama" or "bedrock")
 *
 * @see ResourceDto
 * @see AiFilteringDto
 */
public record AiAnalysisDto(
        int aiConfidence,
        String aiReasoning,
        String aiProvider
) {
}
