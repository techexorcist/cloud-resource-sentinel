package com.cloudsentinel.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiOutputValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validate_validOutput_noWarnings() {
        var root = mapper.createObjectNode();
        var actions = root.putArray("prioritized_actions");
        var action = actions.addObject();
        action.put("resource_id", "i-0abc123def456");
        action.put("action", "TERMINATE");
        action.put("risk", "SAFE");
        action.put("estimated_savings", 42.50);

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.isEmpty(), "Valid output should have no warnings: " + warnings);
    }

    @Test
    void validate_invalidResourceId_producesWarning() {
        var root = mapper.createObjectNode();
        var actions = root.putArray("prioritized_actions");
        var action = actions.addObject();
        action.put("resource_id", "<script>alert('xss')</script>");
        action.put("action", "REVIEW");

        var warnings = AiOutputValidator.validate(root);
        assertFalse(warnings.isEmpty(), "Should warn on HTML in resource_id");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("resource_id")));
    }

    @Test
    void validate_invalidAction_producesWarning() {
        var root = mapper.createObjectNode();
        var actions = root.putArray("prioritized_actions");
        var action = actions.addObject();
        action.put("resource_id", "i-abc123");
        action.put("action", "DESTROY_EVERYTHING");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("action")));
    }

    @Test
    void validate_negativeSavings_producesWarning() {
        var root = mapper.createObjectNode();
        var actions = root.putArray("prioritized_actions");
        var action = actions.addObject();
        action.put("resource_id", "i-abc123");
        action.put("action", "TERMINATE");
        action.put("estimated_savings", -10.0);

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Negative")));
    }

    @Test
    void validate_invalidCategory_producesWarning() {
        var root = mapper.createObjectNode();
        var insights = root.putArray("architecture_insights");
        var insight = insights.addObject();
        insight.put("category", "MALICIOUS_CATEGORY");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("category")));
    }

    @Test
    void validate_nullRoot_emptyWarnings() {
        var warnings = AiOutputValidator.validate(null);
        assertTrue(warnings.isEmpty());
    }

    @Test
    void validate_emptyRoot_emptyWarnings() {
        var warnings = AiOutputValidator.validate(mapper.createObjectNode());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void validate_placeholderLeak_inCostNarrative_producesWarning() {
        var root = mapper.createObjectNode();
        root.put("cost_narrative", "Total spend: $X Spend on resources: $Y Idle waste: $Z");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Placeholder leak") && w.contains("cost_narrative")),
                "Should detect placeholder leak in cost_narrative: " + warnings);
    }

    @Test
    void validate_jsonContamination_inExecutiveSummary_producesWarning() {
        var root = mapper.createObjectNode();
        root.put("executive_summary",
                "Out of 62 resources... {\"resource_id\": \"i-abc\", \"reasoning\": \"test\", \"estimated_savings\": 0.00}");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("JSON contamination") && w.contains("executive_summary")),
                "Should detect JSON contamination in executive_summary: " + warnings);
    }

    @Test
    void validate_cleanExecutiveSummary_noJsonContaminationWarning() {
        var root = mapper.createObjectNode();
        root.put("executive_summary",
                "This account is heavily ALB-dominated with significant idle capacity. The team should focus on consolidating the five idle ALBs.");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("JSON contamination")),
                "Clean narrative should not trigger JSON contamination warning: " + warnings);
    }

    @Test
    void validate_realDollarAmounts_noPlaceholderWarning() {
        var root = mapper.createObjectNode();
        root.put("cost_narrative", "Total spend across 12 resources is $1,274.32. EC2 at $800.00/mo.");

        var warnings = AiOutputValidator.validate(root);
        assertTrue(warnings.stream().noneMatch(w -> w.contains("Placeholder leak")),
                "Real dollar amounts should not trigger placeholder warning: " + warnings);
    }
}
