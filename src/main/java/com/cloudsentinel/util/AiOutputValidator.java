package com.cloudsentinel.util;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Layer 9: Structural schema validation for AI-generated output.
 *
 * <p>Validates every field in the AI's JSON response against strict allowlists
 * before the data reaches the UI. This is proactive defense (allowlist of permitted
 * shapes) complementing the reactive defense in {@link AiResponseParser#sanitizeAiText}
 * (blocklist of dangerous patterns).</p>
 *
 * <p>If the AI emits something dangerous in a pattern the sanitizer didn't anticipate,
 * the schema validator will reject the entire field or response.</p>
 */
public final class AiOutputValidator {

    private static final Logger log = LoggerFactory.getLogger(AiOutputValidator.class);

    private AiOutputValidator() {}

    /** Allowed characters in resource IDs (AWS resource IDs, ARNs, names). */
    private static final Pattern SAFE_RESOURCE_ID = Pattern.compile("^[a-zA-Z0-9_.:/\\-@+()\\[\\],\\s]{1,500}$");

    /** Detects placeholder patterns like $X, $Y, $Z, $W that small models echo from prompt templates. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$[A-Z](?:\\b|[.,;:\\s])");


    /** Allowed action values in prioritized_actions. */
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "TERMINATE", "DOWNSIZE", "REVIEW", "KEEP",
            "REMEDIATE", "ROTATE", "ENABLE", "RESTRICT"
    );

    /** Allowed risk values. */
    private static final Set<String> ALLOWED_RISKS = Set.of(
            "SAFE", "LOW", "MEDIUM", "HIGH", "CRITICAL"
    );

    /** Allowed architecture insight categories. */
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "WASTE", "REDUNDANCY", "SECURITY", "OPTIMIZATION",
            "REGIONAL_ANOMALY", "DEPENDENCY", "COST_TREND", "GOVERNANCE"
    );

    /**
     * Validates the parsed AI JSON root node. Returns a list of validation warnings.
     * Invalid fields are logged as warnings. Use {@link #sanitizeValue(String, String)} to
     * clean individual field values before display.
     *
     * @param root the parsed JSON root from the AI response
     * @return list of validation warnings (empty if all fields are valid)
     */
    public static List<String> validate(JsonNode root) {
        var warnings = new ArrayList<String>();
        if (root == null) return warnings;

        // Validate prioritized_actions
        for (JsonNode action : root.path("prioritized_actions")) {
            validateField(action, "resource_id", SAFE_RESOURCE_ID, warnings);
            validateEnum(action, "action", ALLOWED_ACTIONS, warnings);
            validateEnum(action, "risk", ALLOWED_RISKS, warnings);
            validateNonNegative(action, "estimated_savings", warnings);
        }

        // Validate right_sizing
        for (JsonNode rs : root.path("right_sizing")) {
            validateField(rs, "resource_id", SAFE_RESOURCE_ID, warnings);
            validateNonNegative(rs, "current_cost", warnings);
            validateNonNegative(rs, "projected_cost", warnings);
        }

        // Validate architecture_insights
        for (JsonNode insight : root.path("architecture_insights")) {
            validateEnum(insight, "category", ALLOWED_CATEGORIES, warnings);
        }

        // Validate cleanup_plan
        for (JsonNode phase : root.path("cleanup_plan")) {
            validateNonNegative(phase, "resources_affected", warnings);
            validateNonNegative(phase, "estimated_savings", warnings);
        }

        // Detect placeholder leak in narrative fields — small models echo "$X", "$Y" etc.
        validateNoPlaceholders(root, "cost_narrative", warnings);
        validateNoPlaceholders(root, "risk_overview", warnings);
        validateNoPlaceholders(root, "executive_summary", warnings);

        // Detect JSON contamination — small models dump raw JSON into narrative fields
        validateNoJsonContamination(root, "executive_summary", warnings);

        if (!warnings.isEmpty()) {
            log.warn("AI output validation found {} issues: {}", warnings.size(), warnings);
        }

        return warnings;
    }

    /**
     * Sanitizes a string value for safe display. Rejects values that don't match the
     * safe resource ID pattern by replacing them with a redacted placeholder.
     *
     * @param value the raw value from AI output
     * @param fieldName the field name for logging
     * @return the original value if safe, or "[invalid {fieldName}]" if not
     */
    public static String sanitizeValue(String value, String fieldName) {
        if (value == null || value.isEmpty()) return value;
        if (!SAFE_RESOURCE_ID.matcher(value).matches()) {
            log.warn("AI output field '{}' rejected — unsafe characters: {}", fieldName, value.substring(0, Math.min(50, value.length())));
            return "[invalid " + fieldName + "]";
        }
        return value;
    }

    /**
     * Validates an enum field value. Returns the value if it's in the allowed set,
     * or a default if not.
     *
     * @param value the raw value
     * @param allowed the set of allowed values
     * @param defaultValue the fallback value
     * @return the original value if allowed, or the default
     */
    public static String sanitizeEnum(String value, Set<String> allowed, String defaultValue) {
        if (value != null && allowed.contains(value)) return value;
        if (value != null && !value.isEmpty()) {
            log.warn("AI output enum value '{}' not in allowed set {}, using default '{}'", value, allowed, defaultValue);
        }
        return defaultValue;
    }

    /** Returns the set of allowed action values for external callers. */
    public static Set<String> getAllowedActions() { return ALLOWED_ACTIONS; }

    /** Returns the set of allowed risk values for external callers. */
    public static Set<String> getAllowedRisks() { return ALLOWED_RISKS; }

    private static void validateField(JsonNode node, String field, Pattern pattern, List<String> warnings) {
        String value = node.path(field).asText("");
        if (!value.isEmpty() && !pattern.matcher(value).matches()) {
            warnings.add("Invalid " + field + ": contains disallowed characters: " + value.substring(0, Math.min(50, value.length())));
        }
    }

    private static void validateEnum(JsonNode node, String field, Set<String> allowed, List<String> warnings) {
        String value = node.path(field).asText("");
        if (!value.isEmpty() && !allowed.contains(value)) {
            warnings.add("Invalid " + field + " value '" + value + "' — expected one of: " + allowed);
        }
    }

    private static void validateNonNegative(JsonNode node, String field, List<String> warnings) {
        if (node.has(field) && node.get(field).isNumber() && node.get(field).asDouble() < 0) {
            warnings.add("Negative " + field + ": " + node.get(field).asDouble());
        }
    }

    /** JSON field names that indicate the AI dumped its structured response into a narrative field. */
    private static final List<String> JSON_CONTAMINATION_MARKERS = List.of(
            "\"resource_id\"", "\"prioritized_actions\"", "\"reasoning\"",
            "\"estimated_savings\"", "\"resource_type\"", "\"architecture_insights\""
    );

    private static void validateNoJsonContamination(JsonNode root, String field, List<String> warnings) {
        String value = root.path(field).asText("");
        if (value.isEmpty()) return;
        for (String marker : JSON_CONTAMINATION_MARKERS) {
            if (value.contains(marker)) {
                warnings.add("JSON contamination in " + field + ": contains " + marker
                        + " — model dumped structured response into narrative field");
                return;
            }
        }
    }

    private static void validateNoPlaceholders(JsonNode root, String field, List<String> warnings) {
        String value = root.path(field).asText("");
        if (!value.isEmpty() && PLACEHOLDER_PATTERN.matcher(value).find()) {
            warnings.add("Placeholder leak in " + field + ": contains literal $X/$Y/$Z pattern — model echoed prompt template instead of producing analysis");
        }
    }
}
