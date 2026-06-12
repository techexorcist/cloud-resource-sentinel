package com.cloudsentinel.service;

import com.cloudsentinel.dto.ResourceDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ResourceAnalyzerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Idle",
            "Idle - Consider Downsizing or Terminating",
            "Consider Terminating - Stopped",
            "Delete - Unattached",
            "Release - Instance is Stopped",
            "Unused - No Recent Activity",
            "Empty - No Items",
            "Inactive - 30 Days",
            // Security/governance prefixes
            "Rotate - Key older than 90 days",
            "Enable - CloudTrail not enabled",
            "Restrict - Overly permissive policy",
            "Expired - Certificate expired",
            "Exposed - Public access enabled",
            "Missing - No encryption configured",
            "Stale - No updates in 180 days",
            "Misconfigured - Invalid log retention"
    })
    void isActionable_withActionableRecommendation_returnsTrue(String recommendation) {
        ResourceDto r = new ResourceDto();
        r.setRecommendation(recommendation);
        assertTrue(ResourceAnalyzer.isActionable(r));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Active",
            "Active - Good Utilization",
            "In Use",
            "Moderate Utilization",
            "Low Utilization - Consider Downsizing",
            "Review - Attached to Stopped Instance"
    })
    void isActionable_withNonActionableRecommendation_returnsFalse(String recommendation) {
        ResourceDto r = new ResourceDto();
        r.setRecommendation(recommendation);
        assertFalse(ResourceAnalyzer.isActionable(r));
    }

    @Test
    void isActionable_withNullRecommendation_returnsFalse() {
        ResourceDto r = new ResourceDto();
        r.setRecommendation(null);
        assertFalse(ResourceAnalyzer.isActionable(r));
    }

    @Test
    void isActionable_considerTerminating_matches() {
        ResourceDto r1 = new ResourceDto();
        r1.setRecommendation("Consider Terminating");
        assertTrue(ResourceAnalyzer.isActionable(r1));

        ResourceDto r2 = new ResourceDto();
        r2.setRecommendation("Consider Terminating - Stopped");
        assertTrue(ResourceAnalyzer.isActionable(r2));
    }

    @Test
    void isActionable_stoppedPrefix_matches() {
        ResourceDto r = new ResourceDto();
        r.setRecommendation("Stopped - Consider Deleting");
        assertTrue(ResourceAnalyzer.isActionable(r));
    }

    @Test
    void isActionable_reviewDeleteFailed_notActionable() {
        // "Review - Delete Failed" should NOT be actionable (stuck state, not idle)
        ResourceDto r = new ResourceDto();
        r.setRecommendation("Review - Delete Failed");
        assertFalse(ResourceAnalyzer.isActionable(r));
    }

    @Test
    void isActionable_emptyString_returnsFalse() {
        ResourceDto r = new ResourceDto();
        r.setRecommendation("");
        assertFalse(ResourceAnalyzer.isActionable(r));
    }
}
