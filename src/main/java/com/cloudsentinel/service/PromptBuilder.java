package com.cloudsentinel.service;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds AI prompt strings from scanned resource data and template files.
 *
 * <p>Extracted from {@link AiAnalysisService} to isolate prompt construction logic
 * from AI orchestration. Handles resource formatting, regional distribution analysis,
 * sparse region detection, finding-type counting, and template selection (cost vs.
 * security/governance prompt).</p>
 *
 * <p>The builder is stateless — all state comes from the template strings passed at
 * construction and the resource list passed to {@link #build(List)}.</p>
 */
public class PromptBuilder {

    private final String costPromptTemplate;
    private final String securityPromptTemplate;

    /** Minimum security/governance finding count to trigger the security prompt. */
    static final int SECURITY_PROMPT_THRESHOLD = 5;

    private final String promptVersion;

    public PromptBuilder(String costPromptTemplate, String securityPromptTemplate) {
        this.costPromptTemplate = costPromptTemplate;
        this.securityPromptTemplate = securityPromptTemplate;
        this.promptVersion = extractVersion(costPromptTemplate);
    }

    /** Returns the prompt version string (e.g., "2.1.0") extracted from the template. */
    public String getPromptVersion() { return promptVersion; }

    private static String extractVersion(String template) {
        for (String line : template.split("\n")) {
            if (line.startsWith("PROMPT_VERSION:")) {
                return line.substring("PROMPT_VERSION:".length()).trim();
            }
        }
        return "unknown";
    }

    /**
     * Builds the fully populated AI prompt from a list of scanned resources.
     *
     * <p>Selects the security/governance prompt template when there are at least
     * {@value #SECURITY_PROMPT_THRESHOLD} security/governance findings, or when they
     * make up the majority of findings. Otherwise uses the cost-focused template.</p>
     *
     * @param resources the scanned resources to include in the prompt
     * @return the populated prompt string ready for AI submission
     */
    public String build(List<ResourceDto> resources) {
        var resourceList = new StringBuilder();
        for (ResourceDto r : resources) {
            String line = String.format(
                    "- [%s] %s | %s | %s | region=%s | state=%s | finding_type=%s | severity=%s | cpu=%.1f%% | cost=$%.2f/mo | recommendation=%s",
                    r.getResourceType(), r.getResourceId(), r.getResourceName(),
                    r.getInstanceType(), r.getRegion(), r.getState(),
                    r.getFindingType(), r.getSeverity(),
                    r.getCpuUtilizationAvg(), r.getMonthlyCostUsd(),
                    r.getRecommendation());
            if (r.getCreatedDate() != null && !r.getCreatedDate().isBlank()) {
                line += " | created=" + r.getCreatedDate();
            }
            if (r.getCoveredBy() != null && !r.getCoveredBy().isBlank()) {
                line += " | covered_by=" + r.getCoveredBy();
            }
            if (r.getRecommendationDetail() != null && !r.getRecommendationDetail().isBlank()) {
                line += " | correlation=" + r.getRecommendationDetail();
            }
            resourceList.append(line).append("\n");
        }

        double totalCost = resources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        long idleCount = resources.stream().filter(ResourceAnalyzer::isActionable).count();
        long securityCount = resources.stream().filter(r -> r.getFindingType() == FindingType.SECURITY).count();
        long governanceCount = resources.stream().filter(r -> r.getFindingType() == FindingType.GOVERNANCE).count();

        String regionSummary = buildRegionSummary(resources);
        String sparseRegions = buildSparseRegions(resources);

        String costSummary = buildCostSummary(resources, totalCost, idleCount);
        String costNarrative = buildCostNarrative(resources, totalCost, idleCount);
        String riskOverview = buildRiskOverview(resources);

        boolean useSecurityPrompt = (securityCount + governanceCount) >= SECURITY_PROMPT_THRESHOLD
                || (securityCount + governanceCount) > resources.size() / 2;
        String template = useSecurityPrompt ? securityPromptTemplate : costPromptTemplate;

        return template
                .replace("{{total_resources}}", String.valueOf(resources.size()))
                .replace("{{total_cost}}", String.format("%.2f", totalCost))
                .replace("{{idle_count}}", String.valueOf(idleCount))
                .replace("{{security_count}}", String.valueOf(securityCount))
                .replace("{{governance_count}}", String.valueOf(governanceCount))
                .replace("{{region_summary}}", regionSummary)
                .replace("{{sparse_regions}}", sparseRegions)
                .replace("{{cost_summary}}", costSummary)
                .replace("{{cost_narrative}}", costNarrative)
                .replace("{{risk_overview}}", riskOverview)
                .replace("{{resource_list}}", resourceList.toString());
    }

    /**
     * Pre-computes cost breakdowns so the AI can copy numbers instead of calculating.
     * Includes: spend by service type, top costliest resources, idle vs active spend,
     * total achievable savings, and per-resource savings for actionable resources.
     */
    String buildCostSummary(List<ResourceDto> resources, double totalCost, long idleCount) {
        var sb = new StringBuilder();

        // Spend by service type
        Map<String, Double> spendByType = resources.stream()
                .collect(Collectors.groupingBy(
                        ResourceDto::getResourceType,
                        LinkedHashMap::new,
                        Collectors.summingDouble(ResourceDto::getMonthlyCostUsd)));
        sb.append("SPEND BY SERVICE TYPE:\n");
        spendByType.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sb.append(String.format("  - %s: $%.2f/mo%n", e.getKey(), e.getValue())));

        // Top 5 costliest resources (excluding zero-cost security/governance resources)
        List<ResourceDto> top5 = resources.stream()
                .filter(r -> r.getMonthlyCostUsd() > 0.0)
                .sorted(Comparator.comparingDouble(ResourceDto::getMonthlyCostUsd).reversed())
                .limit(5)
                .toList();
        if (top5.isEmpty()) {
            sb.append("\nTOP COSTLIEST RESOURCES: None — all resources in this scan have no direct cost (security/governance findings).\n");
        } else {
            sb.append("\nTOP 5 COSTLIEST RESOURCES:\n");
            for (int i = 0; i < top5.size(); i++) {
                ResourceDto r = top5.get(i);
                sb.append(String.format("  %d. [%s] %s — $%.2f/mo (region=%s, state=%s)%n",
                        i + 1, r.getResourceType(), r.getResourceId(),
                        r.getMonthlyCostUsd(), r.getRegion(), r.getState()));
            }
        }

        // Idle vs active spend
        double idleSpend = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        double activeSpend = totalCost - idleSpend;
        sb.append(String.format("%nIDLE vs ACTIVE SPEND:%n"));
        sb.append(String.format("  - Idle/actionable resources: %d resources, $%.2f/mo%n", idleCount, idleSpend));
        sb.append(String.format("  - Active/healthy resources: %d resources, $%.2f/mo%n",
                resources.size() - idleCount, activeSpend));
        sb.append(String.format("  - Total achievable savings (if all idle resources are addressed): $%.2f/mo%n", idleSpend));

        // Per-resource savings for actionable resources (AI should copy these)
        List<ResourceDto> actionable = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .sorted(Comparator.comparingDouble(ResourceDto::getMonthlyCostUsd).reversed())
                .toList();
        if (!actionable.isEmpty()) {
            sb.append("\nPER-RESOURCE SAVINGS (copy these values into estimated_savings):\n");
            for (ResourceDto r : actionable) {
                sb.append(String.format("  - %s (%s): $%.2f/mo%n",
                        r.getResourceId(), r.getResourceType(), r.getMonthlyCostUsd()));
            }
        }

        return sb.toString();
    }

    /**
     * Pre-computes the cost_narrative so the AI does not need to calculate dollar amounts.
     * Produces a ready-to-use paragraph the AI can emit verbatim or lightly rephrase.
     */
    String buildCostNarrative(List<ResourceDto> resources, double totalCost, long idleCount) {
        var sb = new StringBuilder();

        // Spend by service type — top contributors
        Map<String, Double> spendByType = resources.stream()
                .collect(Collectors.groupingBy(
                        ResourceDto::getResourceType,
                        LinkedHashMap::new,
                        Collectors.summingDouble(ResourceDto::getMonthlyCostUsd)));
        List<Map.Entry<String, Double>> sortedTypes = spendByType.entrySet().stream()
                .filter(e -> e.getValue() > 0.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        sb.append(String.format("Total monthly spend across %d resources is $%.2f.", resources.size(), totalCost));

        if (!sortedTypes.isEmpty()) {
            sb.append("\nLargest cost drivers by service type:");
            int limit = Math.min(5, sortedTypes.size());
            for (int i = 0; i < limit; i++) {
                var entry = sortedTypes.get(i);
                sb.append(String.format("\n- %s — $%.2f/mo", entry.getKey(), entry.getValue()));
            }
        }

        // Top 5 costliest resources (only those with actual cost)
        List<ResourceDto> top5 = resources.stream()
                .filter(r -> r.getMonthlyCostUsd() > 0.0)
                .sorted(Comparator.comparingDouble(ResourceDto::getMonthlyCostUsd).reversed())
                .limit(5)
                .toList();
        if (!top5.isEmpty()) {
            sb.append("\nTop costliest resources:");
            for (ResourceDto r : top5) {
                sb.append(String.format("\n- %s (%s) — $%.2f/mo", r.getResourceId(), r.getResourceType(), r.getMonthlyCostUsd()));
            }
        }

        // Idle vs active
        double idleSpend = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        double activeSpend = totalCost - idleSpend;
        sb.append(String.format("\nOf the total spend, $%.2f/mo (%d resources) is on idle or actionable resources, while $%.2f/mo (%d resources) is on active, healthy resources. Total achievable savings if all idle resources are addressed: $%.2f/mo.",
                idleSpend, idleCount, activeSpend, resources.size() - idleCount, idleSpend));

        return sb.toString();
    }

    /**
     * Pre-computes a cleanup plan from resource data, grouped into IMMEDIATE / SHORT_TERM / LONG_TERM.
     * Used as a fallback when the AI returns empty actions fields.
     */
    List<com.cloudsentinel.dto.AiInsightDto.CleanupPhase> buildCleanupPlan(List<ResourceDto> resources) {
        var immediate = new ArrayList<ResourceDto>();
        var shortTerm = new ArrayList<ResourceDto>();
        var longTerm = new ArrayList<ResourceDto>();

        for (ResourceDto r : resources) {
            if (!ResourceAnalyzer.isActionable(r)) continue;
            String rec = r.getRecommendation() != null ? r.getRecommendation() : "";
            // Immediate: unattached/unused/empty resources — safe quick wins
            if (rec.startsWith("Release") || rec.startsWith("Delete") || rec.startsWith("Empty")
                    || rec.startsWith("Unused")) {
                immediate.add(r);
            }
            // Short-term: idle/stopped resources, security fixes — needs verification
            else if (rec.startsWith("Idle") || rec.startsWith("Stopped") || rec.startsWith("Rotate")
                    || rec.startsWith("Enable") || rec.startsWith("Restrict") || rec.startsWith("Expired")
                    || rec.startsWith("Exposed") || rec.startsWith("Missing")) {
                shortTerm.add(r);
            }
            // Long-term: everything else (stale, inactive, misconfigured)
            else if (rec.startsWith("Stale") || rec.startsWith("Inactive") || rec.startsWith("Misconfigured")
                    || rec.startsWith("Consider")) {
                longTerm.add(r);
            }
        }

        var plan = new ArrayList<com.cloudsentinel.dto.AiInsightDto.CleanupPhase>();
        if (!immediate.isEmpty()) {
            double savings = immediate.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            String actions = buildPhaseActions(immediate);
            plan.add(new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                    "IMMEDIATE", actions, immediate.size(), savings,
                    "Low risk — these resources are unattached or unused"));
        }
        if (!shortTerm.isEmpty()) {
            double savings = shortTerm.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            String actions = buildPhaseActions(shortTerm);
            plan.add(new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                    "SHORT_TERM", actions, shortTerm.size(), savings,
                    "Medium risk — verify with team before acting"));
        }
        if (!longTerm.isEmpty()) {
            double savings = longTerm.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            String actions = buildPhaseActions(longTerm);
            plan.add(new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                    "LONG_TERM", actions, longTerm.size(), savings,
                    "Strategic — requires planning and coordination"));
        }
        return plan;
    }

    private String buildPhaseActions(List<ResourceDto> resources) {
        // Group by resource type and summarize
        Map<String, List<ResourceDto>> byType = resources.stream()
                .collect(Collectors.groupingBy(ResourceDto::getResourceType, LinkedHashMap::new, Collectors.toList()));
        var parts = new ArrayList<String>();
        for (var entry : byType.entrySet()) {
            List<ResourceDto> group = entry.getValue();
            String firstRec = group.getFirst().getRecommendation();
            String verb = firstRec != null && firstRec.contains(" - ") ? firstRec.substring(0, firstRec.indexOf(" - ")).trim()
                    : firstRec != null && firstRec.contains(" —") ? firstRec.substring(0, firstRec.indexOf(" —")).trim()
                    : firstRec != null ? firstRec.split("\\s+")[0] : "Review";
            double cost = group.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            String costStr = cost > 0 ? String.format(" ($%.2f/mo)", cost) : "";
            parts.add(String.format("%s %d %s%s%s",
                    verb, group.size(), entry.getKey(), group.size() > 1 ? "s" : "", costStr));
        }
        return String.join("; ", parts);
    }

    /**
     * Pre-computes the factual statistics for the executive summary.
     * This is prepended to the AI's narrative judgment so the stats are always correct.
     */
    String buildExecutivePreamble(List<ResourceDto> resources, double totalCost, long idleCount) {
        var sb = new StringBuilder();
        sb.append(String.format("This account has %d resources with a total monthly spend of $%.2f. ",
                resources.size(), totalCost));

        double idleSpend = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        sb.append(String.format("%d resources are actionable ($%.2f/mo potential savings) and %d are healthy. ",
                idleCount, idleSpend, resources.size() - idleCount));

        // Top 3 savings opportunities (only resources with actual cost)
        List<ResourceDto> topSavings = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .filter(r -> r.getMonthlyCostUsd() > 0.0)
                .sorted(Comparator.comparingDouble(ResourceDto::getMonthlyCostUsd).reversed())
                .limit(3)
                .toList();
        if (!topSavings.isEmpty()) {
            sb.append("Top savings opportunities: ");
            for (int i = 0; i < topSavings.size(); i++) {
                ResourceDto r = topSavings.get(i);
                if (i > 0 && i == topSavings.size() - 1) sb.append(" and ");
                else if (i > 0) sb.append(", ");
                sb.append(String.format("%s (%s) at $%.2f/mo", r.getResourceId(), r.getResourceType(), r.getMonthlyCostUsd()));
            }
            sb.append(".");
        } else if (idleCount > 0) {
            sb.append("All actionable resources are security/governance findings with no direct cost.");
        }

        return sb.toString();
    }

    /**
     * Pre-computes the risk_overview so the AI does not need to aggregate risk counts.
     * Produces a ready-to-use paragraph summarizing resource counts by actionability.
     */
    String buildRiskOverview(List<ResourceDto> resources) {
        long actionableCount = resources.stream().filter(ResourceAnalyzer::isActionable).count();
        long healthyCount = resources.size() - actionableCount;

        // Count by severity
        Map<String, Long> severityCounts = resources.stream()
                .filter(ResourceAnalyzer::isActionable)
                .collect(Collectors.groupingBy(
                        r -> r.getSeverity() != null ? r.getSeverity().name() : "INFO",
                        Collectors.counting()));

        var sb = new StringBuilder();
        sb.append(String.format("Out of %d total resources, %d require action and %d are healthy.",
                resources.size(), actionableCount, healthyCount));

        if (!severityCounts.isEmpty()) {
            // Sort severity: CRITICAL, HIGH, MEDIUM, LOW, INFO
            var severityOrder = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
            sb.append("\nActionable resources by severity:");
            for (String sev : severityOrder) {
                Long count = severityCounts.get(sev);
                if (count != null && count > 0) {
                    sb.append(String.format("\n- %s: %d", sev, count));
                }
            }
        }

        if (actionableCount > 0) {
            List<ResourceDto> critical = resources.stream()
                    .filter(ResourceAnalyzer::isActionable)
                    .filter(r -> r.getSeverity() != null && r.getSeverity().name().equals("CRITICAL"))
                    .toList();
            List<ResourceDto> high = resources.stream()
                    .filter(ResourceAnalyzer::isActionable)
                    .filter(r -> r.getSeverity() != null && r.getSeverity().name().equals("HIGH"))
                    .toList();
            if (!critical.isEmpty()) {
                sb.append(String.format("\n%d CRITICAL resource(s) need immediate attention.", critical.size()));
            } else if (!high.isEmpty()) {
                sb.append(String.format("\n%d HIGH-severity resource(s) should be reviewed promptly.", high.size()));
            }
        }

        sb.append("\nVerify each recommendation before acting — create snapshots or backups as safeguards for any termination or resize actions.");
        return sb.toString();
    }

    private String buildRegionSummary(List<ResourceDto> resources) {
        var regionCounts = new LinkedHashMap<String, Long>();
        var regionCosts = new LinkedHashMap<String, Double>();
        for (ResourceDto r : resources) {
            String region = r.getRegion() != null ? r.getRegion() : "unknown";
            regionCounts.merge(region, 1L, Long::sum);
            regionCosts.merge(region, r.getMonthlyCostUsd(), Double::sum);
        }
        var sb = new StringBuilder();
        regionCounts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format(
                        "  - %s: %d resources, $%.2f/mo%n",
                        e.getKey(), e.getValue(), regionCosts.getOrDefault(e.getKey(), 0.0))));
        return sb.toString();
    }

    private String buildSparseRegions(List<ResourceDto> resources) {
        var regionCounts = new LinkedHashMap<String, Long>();
        for (ResourceDto r : resources) {
            String region = r.getRegion() != null ? r.getRegion() : "unknown";
            regionCounts.merge(region, 1L, Long::sum);
        }
        var sparse = regionCounts.entrySet().stream()
                .filter(e -> e.getValue() <= 2 && !"global".equals(e.getKey()))
                .map(e -> e.getKey() + " (" + e.getValue() + " resource" + (e.getValue() > 1 ? "s" : "") + ")")
                .toList();
        return sparse.isEmpty() ? "  None detected" : String.join("\n  ", sparse);
    }
}
