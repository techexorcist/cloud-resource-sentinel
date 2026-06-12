package com.cloudsentinel.service;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTest {

    private static final String COST_TEMPLATE = "COST_PROMPT: {{total_resources}} resources, ${{total_cost}}, {{idle_count}} actionable. {{region_summary}} {{sparse_regions}} {{cost_summary}} {{cost_narrative}} {{risk_overview}} {{resource_list}}";
    private static final String SECURITY_TEMPLATE = "SECURITY_PROMPT: {{total_resources}} resources, {{security_count}} security, {{governance_count}} governance. {{region_summary}} {{sparse_regions}} {{cost_summary}} {{cost_narrative}} {{risk_overview}} {{resource_list}}";

    private final PromptBuilder builder = new PromptBuilder(COST_TEMPLATE, SECURITY_TEMPLATE);

    @Test
    void build_usesCostPrompt_whenNoSecurityFindings() {
        List<ResourceDto> resources = makeResources(10, FindingType.COST);
        String result = builder.build(resources);
        assertTrue(result.startsWith("COST_PROMPT:"), "Should use cost template, got: " + result.substring(0, 30));
    }

    @Test
    void build_usesCostPrompt_whenFewerThan5SecurityFindings() {
        List<ResourceDto> resources = new ArrayList<>();
        resources.addAll(makeResources(20, FindingType.COST));
        resources.addAll(makeResources(4, FindingType.SECURITY));
        String result = builder.build(resources);
        assertTrue(result.startsWith("COST_PROMPT:"), "4 security findings should use cost template");
    }

    @Test
    void build_usesSecurityPrompt_whenExactly5SecurityFindings() {
        List<ResourceDto> resources = new ArrayList<>();
        resources.addAll(makeResources(20, FindingType.COST));
        resources.addAll(makeResources(5, FindingType.SECURITY));
        String result = builder.build(resources);
        assertTrue(result.startsWith("SECURITY_PROMPT:"), "5 security findings should trigger security template");
    }

    @Test
    void build_usesSecurityPrompt_whenSecurityMajority() {
        List<ResourceDto> resources = new ArrayList<>();
        resources.addAll(makeResources(2, FindingType.COST));
        resources.addAll(makeResources(3, FindingType.SECURITY));
        String result = builder.build(resources);
        assertTrue(result.startsWith("SECURITY_PROMPT:"), "Security majority should trigger security template");
    }

    @Test
    void build_usesSecurityPrompt_whenGovernancePlusSecurityReachThreshold() {
        List<ResourceDto> resources = new ArrayList<>();
        resources.addAll(makeResources(20, FindingType.COST));
        resources.addAll(makeResources(3, FindingType.SECURITY));
        resources.addAll(makeResources(2, FindingType.GOVERNANCE));
        String result = builder.build(resources);
        assertTrue(result.startsWith("SECURITY_PROMPT:"), "3 security + 2 governance = 5 should trigger security template");
    }

    @Test
    void build_populatesPlaceholders() {
        List<ResourceDto> resources = makeResources(3, FindingType.COST);
        String result = builder.build(resources);
        assertTrue(result.contains("3 resources"), "Should replace total_resources");
        assertFalse(result.contains("{{"), "No unreplaced placeholders should remain");
    }

    @Test
    void build_includesResourceLines() {
        List<ResourceDto> resources = makeResources(2, FindingType.COST);
        resources.get(0).setResourceId("test-id-1");
        resources.get(1).setResourceId("test-id-2");
        String result = builder.build(resources);
        assertTrue(result.contains("test-id-1"));
        assertTrue(result.contains("test-id-2"));
    }

    @Test
    void build_includesPreComputedCostSummary() {
        List<ResourceDto> resources = new ArrayList<>();
        ResourceDto idle = makeResource(FindingType.COST);
        idle.setResourceId("i-idle-123");
        idle.setMonthlyCostUsd(50.0);
        idle.setRecommendation("Idle — no activity for 30 days");
        resources.add(idle);

        ResourceDto active = makeResource(FindingType.COST);
        active.setResourceId("i-active-456");
        active.setMonthlyCostUsd(100.0);
        active.setRecommendation("Active");
        resources.add(active);

        String result = builder.build(resources);
        // Pre-computed cost summary should contain spend breakdown
        assertTrue(result.contains("SPEND BY SERVICE TYPE:"), "Should include spend by service type");
        assertTrue(result.contains("TOP 5 COSTLIEST RESOURCES:"), "Should include top costliest");
        assertTrue(result.contains("IDLE vs ACTIVE SPEND:"), "Should include idle vs active");
        assertTrue(result.contains("$100.00"), "Should contain active resource cost");
        assertTrue(result.contains("$50.00"), "Should contain idle resource cost");
        assertTrue(result.contains("PER-RESOURCE SAVINGS"), "Should include per-resource savings for actionable resources");
        assertTrue(result.contains("i-idle-123"), "Per-resource savings should reference idle resource ID");
    }

    @Test
    void build_costSummary_omitsPerResourceSavings_whenNoActionable() {
        List<ResourceDto> resources = makeResources(3, FindingType.COST);
        // All resources have "Active" recommendation (not actionable)
        String result = builder.build(resources);
        assertFalse(result.contains("PER-RESOURCE SAVINGS"), "Should not include per-resource savings when no actionable resources");
    }

    @Test
    void build_detectsSparseRegions() {
        List<ResourceDto> resources = new ArrayList<>();
        // 10 resources in us-east-1, 1 resource in ap-south-1
        for (int i = 0; i < 10; i++) {
            ResourceDto r = makeResource(FindingType.COST);
            r.setRegion("us-east-1");
            resources.add(r);
        }
        ResourceDto sparse = makeResource(FindingType.COST);
        sparse.setRegion("ap-south-1");
        resources.add(sparse);

        String result = builder.build(resources);
        assertTrue(result.contains("ap-south-1"), "Sparse region should appear in output");
    }

    private List<ResourceDto> makeResources(int count, FindingType type) {
        List<ResourceDto> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(makeResource(type));
        }
        return list;
    }

    private ResourceDto makeResource(FindingType type) {
        ResourceDto r = new ResourceDto();
        r.setFindingType(type);
        r.setResourceType("EC2");
        r.setResourceId("i-" + System.nanoTime());
        r.setResourceName("test");
        r.setRegion("us-east-1");
        r.setState("running");
        r.setRecommendation("Active");
        r.setMonthlyCostUsd(10.0);
        return r;
    }
}
