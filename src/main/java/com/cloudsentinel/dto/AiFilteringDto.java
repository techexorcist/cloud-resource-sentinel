package com.cloudsentinel.dto;

/**
 * Data Transfer Object representing the AI filtering metadata for a scan analysis response.
 *
 * <p>When AI filtering is enabled, the system uses an AI model to review candidate idle resources
 * identified by the scanners and determine which ones are truly idle versus those that may appear
 * idle but are actually in active use. This record captures the summary of that filtering process,
 * including how many candidates were evaluated and how many passed the AI's scrutiny.</p>
 *
 * <p>This DTO is included in {@link AnalysisResponse} to inform consumers whether AI filtering
 * was applied, which provider/model performed the filtering, and the reduction ratio
 * (totalCandidates vs. trulyIdleCount).</p>
 *
 * <p>Serialized to JSON using the global Jackson {@code SNAKE_CASE} naming strategy, producing
 * field names such as {@code total_candidates}, {@code truly_idle_count}, and {@code ai_model}.</p>
 *
 * <p>As a Java {@code record}, instances are immutable. Two factory methods ({@link #disabled()}
 * and {@link #enabled(String, Integer, Integer, String)}) are provided for convenient construction
 * in the two common scenarios.</p>
 *
 * @param enabled         whether AI filtering was active for this scan
 * @param provider        the AI provider used for filtering (e.g., "ollama" or "bedrock"), or {@code null} if disabled
 * @param totalCandidates the total number of candidate idle resources presented to the AI for evaluation,
 *                        or {@code null} if filtering was disabled
 * @param trulyIdleCount  the number of resources the AI confirmed as truly idle after evaluation,
 *                        or {@code null} if filtering was disabled
 * @param aiModel         the specific AI model identifier used (e.g., "llama3.2", "claude-sonnet"),
 *                        or {@code null} if filtering was disabled
 *
 * @see AnalysisResponse
 * @see AiAnalysisDto
 */
public record AiFilteringDto(
        boolean enabled,
        String provider,
        Integer totalCandidates,
        Integer trulyIdleCount,
        String aiModel
) {
    /**
     * Creates an {@code AiFilteringDto} representing a scan where AI filtering was not used.
     *
     * <p>All fields other than {@code enabled} are set to {@code null}, and {@code enabled} is {@code false}.
     * This is the standard factory method used when the user did not request AI filtering or when
     * no AI provider was available.</p>
     *
     * @return an {@code AiFilteringDto} with filtering disabled and all metadata fields set to {@code null}
     */
    public static AiFilteringDto disabled() {
        return new AiFilteringDto(false, null, null, null, null);
    }

    /**
     * Creates an {@code AiFilteringDto} representing a scan where AI filtering was successfully applied.
     *
     * <p>The {@code enabled} flag is set to {@code true}, and the provided metadata captures
     * which AI provider and model performed the filtering, along with the candidate and idle counts.</p>
     *
     * @param provider        the AI provider that performed the filtering (e.g., "ollama" or "bedrock")
     * @param totalCandidates the total number of candidate idle resources evaluated by the AI
     * @param trulyIdleCount  the number of resources confirmed as truly idle by the AI
     * @param aiModel         the specific model identifier used for filtering
     * @return an {@code AiFilteringDto} with filtering enabled and the specified metadata
     */
    public static AiFilteringDto enabled(String provider, Integer totalCandidates, Integer trulyIdleCount, String aiModel) {
        return new AiFilteringDto(true, provider, totalCandidates, trulyIdleCount, aiModel);
    }
}
