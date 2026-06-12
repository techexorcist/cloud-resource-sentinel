package com.cloudsentinel.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for processing AI model responses.
 *
 * <p>Provides two core functions:</p>
 * <ul>
 *   <li>{@link #extractJson(String)} — strips markdown code fences and extracts the JSON object
 *       from raw AI output. Used by {@link com.cloudsentinel.service.AiAnalysisService} to parse
 *       structured insight responses.</li>
 *   <li>{@link #sanitizeAiText(String)} — scans AI-generated text for executable command patterns
 *       (e.g., {@code aws ec2 terminate-instances}, {@code terraform destroy}, {@code kubectl delete})
 *       and redacts them. This is the enforcement layer for the AI prompt safety guardrail —
 *       the prompt tells the model not to emit commands, but this method catches any that slip through.</li>
 * </ul>
 *
 * <p>Stateless utility class with only static methods and a private constructor.</p>
 */
public final class AiResponseParser {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private AiResponseParser() {
    }

    /** Patterns that look like executable commands — should never appear in AI output. */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("(?i)\\baws\\s+(ec2|s3|rds|iam|lambda|ecs|eks|cloudformation)\\s+\\w+"),
            Pattern.compile("(?i)\\bterraform\\s+(apply|destroy|plan)"),
            Pattern.compile("(?i)\\bkubectl\\s+(delete|apply|exec|scale)"),
            Pattern.compile("(?i)\\bcurl\\s+(-X\\s+)?(DELETE|PUT|POST)\\b"),
            Pattern.compile("(?i)\\brm\\s+-rf\\b"),
            Pattern.compile("(?i)\\bdocker\\s+(rm|kill|stop)\\b")
    );

    /**
     * Scans a text string for executable command patterns that should never appear in
     * AI-generated recommendations.
     *
     * <p>This is the enforcement layer for the AI prompt guardrail. The prompt tells
     * the model not to emit commands, but smaller models may ignore the instruction.
     * This method catches any that slip through.</p>
     *
     * @param text the AI-generated text to check (reasoning, recommendation, etc.)
     * @return the sanitized text with command-like patterns redacted, or the original
     *         text if no dangerous patterns were found
     */
    public static String sanitizeAiText(String text) {
        if (text == null || text.isBlank()) return text;
        String result = text;
        for (Pattern pattern : DANGEROUS_PATTERNS) {
            result = pattern.matcher(result).replaceAll("[command redacted — Sentinel is read-only]");
        }
        return result;
    }

    /**
     * Extracts a JSON object from an AI response, stripping markdown code fences if present.
     *
     * <p>Handles both {@code ```json} and plain {@code ```} fences. If no JSON object braces
     * are found, returns the trimmed input as-is for downstream error handling.</p>
     *
     * @param response the raw AI response text; may be {@code null}
     * @return the extracted JSON string, or empty string if input is null/blank
     */
    public static String extractJson(String response) {
        if (response == null || response.isBlank()) return "";
        String trimmed = response.strip();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

}
