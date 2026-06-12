package com.cloudsentinel.controller;

import com.cloudsentinel.dto.AiAnalysisDto;
import com.cloudsentinel.dto.AiFilteringDto;
import com.cloudsentinel.dto.AnalysisResponse;
import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    @Test
    void resourceDto_serializesToSnakeCase() throws Exception {
        ResourceDto dto = new ResourceDto();
        dto.setResourceType("EC2");
        dto.setResourceId("i-12345");
        dto.setMonthlyCostUsd(42.50);
        dto.setCpuUtilizationAvg(3.2);
        dto.setResourceName("web-server");
        dto.setInstanceType("t3.micro");
        dto.setState("running");
        dto.setRecommendation("Idle");
        dto.setCreatedDate("2025-01-15");
        dto.setRegion("us-east-1");

        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);

        assertNotNull(node.get("resource_type"));
        assertNotNull(node.get("monthly_cost_usd"));
        assertNotNull(node.get("cpu_utilization_avg"));
        assertNotNull(node.get("resource_id"));
        assertNotNull(node.get("resource_name"));
        assertNotNull(node.get("instance_type"));
        assertNotNull(node.get("created_date"));

        assertNull(node.get("resourceType"));
        assertNull(node.get("monthlyCostUsd"));
        assertNull(node.get("cpuUtilizationAvg"));
        assertNull(node.get("resourceId"));
        assertNull(node.get("resourceName"));
        assertNull(node.get("instanceType"));
        assertNull(node.get("createdDate"));

        assertEquals("EC2", node.get("resource_type").asText());
        assertEquals(42.50, node.get("monthly_cost_usd").asDouble());
    }

    @Test
    void resourceDto_findingTypeDefaultsToCostAndSerializesToSnakeCase() throws Exception {
        ResourceDto dto = new ResourceDto();
        dto.setResourceType("EC2");
        dto.setResourceId("i-12345");

        // Default is COST
        assertEquals(FindingType.COST, dto.getFindingType());

        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);
        assertNotNull(node.get("finding_type"));
        assertEquals("COST", node.get("finding_type").asText());
        assertNull(node.get("findingType")); // Must be snake_case

        // Set to SECURITY
        dto.setFindingType(FindingType.SECURITY);
        json = mapper.writeValueAsString(dto);
        node = mapper.readTree(json);
        assertEquals("SECURITY", node.get("finding_type").asText());

        // Set to GOVERNANCE
        dto.setFindingType(FindingType.GOVERNANCE);
        json = mapper.writeValueAsString(dto);
        node = mapper.readTree(json);
        assertEquals("GOVERNANCE", node.get("finding_type").asText());
    }

    @Test
    void resourceDto_findingTypeNullDefaultsToCost() {
        ResourceDto dto = new ResourceDto();
        dto.setFindingType(null);
        assertEquals(FindingType.COST, dto.getFindingType());
    }

    @Test
    void resourceDto_severityDefaultsToInfoAndSerializesToSnakeCase() throws Exception {
        ResourceDto dto = new ResourceDto();
        dto.setResourceType("IAM User");
        dto.setResourceId("user-1");
        assertEquals(Severity.INFO, dto.getSeverity());

        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);
        assertNotNull(node.get("severity"));
        assertEquals("INFO", node.get("severity").asText());

        dto.setSeverity(Severity.CRITICAL);
        json = mapper.writeValueAsString(dto);
        node = mapper.readTree(json);
        assertEquals("CRITICAL", node.get("severity").asText());
    }

    @Test
    void resourceDto_severityNullDefaultsToInfo() {
        ResourceDto dto = new ResourceDto();
        dto.setSeverity(null);
        assertEquals(Severity.INFO, dto.getSeverity());
    }

    @Test
    void resourceDto_omitsNullAiAnalysisAndCoveredBy() throws Exception {
        ResourceDto dto = new ResourceDto();
        dto.setResourceType("EC2");
        dto.setResourceId("i-99999");

        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);

        assertNull(node.get("ai_analysis"));
        assertNull(node.get("covered_by"));
    }

    @Test
    void resourceDto_includesNestedAiAnalysis() throws Exception {
        ResourceDto dto = new ResourceDto();
        dto.setResourceType("EC2");
        dto.setResourceId("i-12345");
        dto.setAiAnalysis(new AiAnalysisDto(92, "Stopped for 14 days", "ollama"));

        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);

        JsonNode ai = node.get("ai_analysis");
        assertNotNull(ai);
        assertEquals(92, ai.get("ai_confidence").asInt());
        assertEquals("Stopped for 14 days", ai.get("ai_reasoning").asText());
        assertEquals("ollama", ai.get("ai_provider").asText());
    }

    @Test
    void analysisResponse_serializesToSnakeCase() throws Exception {
        AnalysisResponse response = new AnalysisResponse();
        response.setTotalResources(10);
        response.setTotalMonthlyCost(500.0);
        response.setActionableFindingsCount(3);
        response.setPotentialSavings(150.0);
        response.setResources(List.of());
        response.setAnalyzedRegions(List.of("us-east-1", "eu-west-1"));
        response.setTimestamp("2025-01-15T10:00:00Z");
        response.setAiFiltering(AiFilteringDto.disabled());
        response.setCostFindingsCount(7);
        response.setSecurityFindingsCount(2);
        response.setGovernanceFindingsCount(1);

        String json = mapper.writeValueAsString(response);
        JsonNode node = mapper.readTree(json);

        assertEquals(10, node.get("total_resources").asInt());
        assertEquals(500.0, node.get("total_monthly_cost").asDouble());
        // actionable_findings_count is the new name; idle_resources_count is kept for backward compat
        assertEquals(3, node.get("actionable_findings_count").asInt());
        assertEquals(3, node.get("idle_resources_count").asInt());
        assertEquals(150.0, node.get("potential_savings").asDouble());
        assertNotNull(node.get("analyzed_regions"));
        assertNotNull(node.get("ai_filtering"));

        // Per-finding-type counts
        assertEquals(7, node.get("cost_findings_count").asInt());
        assertEquals(2, node.get("security_findings_count").asInt());
        assertEquals(1, node.get("governance_findings_count").asInt());

        assertNull(node.get("totalResources"));
        assertNull(node.get("totalMonthlyCost"));
        assertNull(node.get("potentialSavings"));
    }

    @Test
    void aiFilteringDto_disabledSerializesCorrectly() throws Exception {
        AiFilteringDto dto = AiFilteringDto.disabled();
        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);

        assertFalse(node.get("enabled").asBoolean());
        assertTrue(node.get("provider").isNull());
        assertTrue(node.get("total_candidates").isNull());
        assertTrue(node.get("truly_idle_count").isNull());
        assertTrue(node.get("ai_model").isNull());
    }

    @Test
    void aiFilteringDto_enabledSerializesCorrectly() throws Exception {
        AiFilteringDto dto = AiFilteringDto.enabled("bedrock", 25, 8, "claude-3");
        String json = mapper.writeValueAsString(dto);
        JsonNode node = mapper.readTree(json);

        assertTrue(node.get("enabled").asBoolean());
        assertEquals("bedrock", node.get("provider").asText());
        assertEquals(25, node.get("total_candidates").asInt());
        assertEquals(8, node.get("truly_idle_count").asInt());
        assertEquals("claude-3", node.get("ai_model").asText());
    }
}
