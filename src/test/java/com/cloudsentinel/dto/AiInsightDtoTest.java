package com.cloudsentinel.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiInsightDtoTest {

    @Test
    void empty_returnsNonNullWithEmptyLists() {
        AiInsightDto dto = AiInsightDto.empty();
        assertNotNull(dto);
        assertNotNull(dto.prioritizedActions());
        assertNotNull(dto.rightSizing());
        assertNotNull(dto.architectureInsights());
        assertNotNull(dto.wellArchitected());
        assertNotNull(dto.cleanupPlan());
        assertTrue(dto.prioritizedActions().isEmpty());
        assertTrue(dto.rightSizing().isEmpty());
        assertTrue(dto.architectureInsights().isEmpty());
        assertTrue(dto.wellArchitected().isEmpty());
        assertTrue(dto.cleanupPlan().isEmpty());
    }

    @Test
    void empty_hasNullProviderModelAndAiUsage() {
        AiInsightDto dto = AiInsightDto.empty();
        assertNull(dto.provider());
        assertNull(dto.model());
        assertNull(dto.aiUsage());
        assertNull(dto.executiveSummary());
        assertNull(dto.riskOverview());
        assertNull(dto.costNarrative());
    }

    @Test
    void recordConstruction_allFields() {
        AiUsageDto usage = AiUsageDto.of("ollama", "llama3", 100, 200, 300, 5000, 1000, 2000);
        List<AiInsightDto.ActionItem> actions = List.of(
                new AiInsightDto.ActionItem("i-123", "EC2", "us-east-1", "TERMINATE", "Idle for 30 days", "LOW", 50.0)
        );
        List<AiInsightDto.RightSizingSuggestion> rightSizing = List.of(
                new AiInsightDto.RightSizingSuggestion("i-456", "m5.xlarge", "m5.large", 100.0, 50.0, "Low CPU")
        );
        List<AiInsightDto.ArchitectureInsight> archInsights = List.of(
                new AiInsightDto.ArchitectureInsight("OPTIMIZATION", "Spread across regions", "Consolidate")
        );
        List<AiInsightDto.WellArchitected> wellArch = List.of(
                new AiInsightDto.WellArchitected("COST", "Over-provisioned", "Right-size instances")
        );
        List<AiInsightDto.CleanupPhase> cleanup = List.of(
                new AiInsightDto.CleanupPhase("SHORT_TERM", "Delete unused EBS", 5, 25.0, "LOW")
        );

        AiInsightDto dto = new AiInsightDto(
                "Executive summary here", actions, rightSizing, archInsights, wellArch, cleanup,
                "Risk overview", "Cost narrative", "ollama", "llama3", usage
        );

        assertEquals("Executive summary here", dto.executiveSummary());
        assertEquals(1, dto.prioritizedActions().size());
        assertEquals(1, dto.rightSizing().size());
        assertEquals(1, dto.architectureInsights().size());
        assertEquals(1, dto.wellArchitected().size());
        assertEquals(1, dto.cleanupPlan().size());
        assertEquals("Risk overview", dto.riskOverview());
        assertEquals("Cost narrative", dto.costNarrative());
        assertEquals("ollama", dto.provider());
        assertEquals("llama3", dto.model());
        assertNotNull(dto.aiUsage());
    }

    @Test
    void actionItem_recordConstruction() {
        AiInsightDto.ActionItem item = new AiInsightDto.ActionItem(
                "i-abc123", "EC2", "us-west-2", "TERMINATE", "No activity in 90 days", "LOW", 45.50
        );
        assertEquals("i-abc123", item.resourceId());
        assertEquals("EC2", item.resourceType());
        assertEquals("us-west-2", item.region());
        assertEquals("TERMINATE", item.action());
        assertEquals("No activity in 90 days", item.reasoning());
        assertEquals("LOW", item.risk());
        assertEquals(45.50, item.estimatedSavings());
    }

    @Test
    void rightSizingSuggestion_recordConstruction() {
        AiInsightDto.RightSizingSuggestion suggestion = new AiInsightDto.RightSizingSuggestion(
                "i-xyz789", "r5.2xlarge", "r5.xlarge", 200.0, 100.0, "Average CPU is 15%"
        );
        assertEquals("i-xyz789", suggestion.resourceId());
        assertEquals("r5.2xlarge", suggestion.currentType());
        assertEquals("r5.xlarge", suggestion.recommendedType());
        assertEquals(200.0, suggestion.currentCost());
        assertEquals(100.0, suggestion.projectedCost());
        assertEquals("Average CPU is 15%", suggestion.reasoning());
    }

    @Test
    void architectureInsight_recordConstruction() {
        AiInsightDto.ArchitectureInsight insight = new AiInsightDto.ArchitectureInsight(
                "SECURITY", "Public S3 buckets found", "Enable bucket policies"
        );
        assertEquals("SECURITY", insight.category());
        assertEquals("Public S3 buckets found", insight.finding());
        assertEquals("Enable bucket policies", insight.recommendation());
    }

    @Test
    void wellArchitected_recordConstruction() {
        AiInsightDto.WellArchitected wa = new AiInsightDto.WellArchitected(
                "OPERATIONAL", "No CloudWatch alarms", "Set up monitoring"
        );
        assertEquals("OPERATIONAL", wa.category());
        assertEquals("No CloudWatch alarms", wa.finding());
        assertEquals("Set up monitoring", wa.detail());
    }

    @Test
    void cleanupPhase_recordConstruction() {
        AiInsightDto.CleanupPhase phase = new AiInsightDto.CleanupPhase(
                "IMMEDIATE", "Delete unattached EBS volumes", 12, 60.0, "LOW"
        );
        assertEquals("IMMEDIATE", phase.phase());
        assertEquals("Delete unattached EBS volumes", phase.actions());
        assertEquals(12, phase.resourcesAffected());
        assertEquals(60.0, phase.estimatedSavings());
        assertEquals("LOW", phase.riskLevel());
    }
}
