package com.cloudsentinel.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.ScanReportDto;
import com.cloudsentinel.service.ReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Reports API group, handling cached report retrieval, comparison, and cache management.
 *
 * <p>This controller provides endpoints for accessing cached scan reports, comparing reports across time,
 * checking report status, and managing the report cache. Up to 3 reports are cached per account,
 * enabling diff comparison between scans.</p>
 *
 * @see com.cloudsentinel.service.ReportService
 */
@RestController
@Tag(name = "Reports")
public class ReportController {

    private final ReportService reportService;

    /**
     * Constructs the report controller with its required service dependency.
     *
     * @param reportService the report service for cached report retrieval and comparison
     */
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Checks whether a recent cached report exists for the given profile.
     *
     * <p>GET {@code /analyse/status?profileName=...}</p>
     *
     * @param profileName the AWS profile name (optional, defaults to "default")
     * @return a map with {@code account_id}, {@code has_recent_report} (boolean),
     *         {@code report_age_hours} (-1 if no report), and {@code report_count}
     */
    @Operation(summary = "Check report status", description = "Checks if a recent cached report exists for the given profile. Returns account ID, whether a recent report exists, and its age in hours.")
    @GetMapping("/analyse/status")
    public Map<String, Object> checkReportStatus(@RequestParam(required = false) String profileName) {
        String accountId = reportService.resolveAccountId(profileName);
        boolean hasRecent = reportService.hasRecentReport(accountId);
        var latest = reportService.getLatestReport(accountId);

        return Map.of(
                "account_id", accountId,
                "has_recent_report", hasRecent,
                "report_age_hours", latest.map(r ->
                        Duration.between(r.getScannedAt(), Instant.now()).toHours()
                ).orElse(-1L),
                "report_count", reportService.getReportsForAccount(accountId).size()
        );
    }

    /**
     * Returns the most recent cached scan report for the given profile.
     *
     * <p>GET {@code /analyse/cached?profileName=...}</p>
     *
     * @param profileName the AWS profile name (optional, defaults to "default")
     * @return 200 with the {@link ScanReportDto}, or 204 (No Content) if no cached report exists
     */
    @Operation(summary = "Get latest cached report", description = "Returns the most recent scan report for the given profile. Returns 204 if no report exists.")
    @GetMapping("/analyse/cached")
    public ResponseEntity<ScanReportDto> getCachedReport(@RequestParam(required = false) String profileName) {
        String accountId = reportService.resolveAccountId(profileName);
        return reportService.getLatestReport(accountId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Returns all cached reports for the given profile (up to 3), sorted newest first.
     *
     * <p>GET {@code /analyse/reports?profileName=...}</p>
     *
     * @param profileName the AWS profile name (optional, defaults to "default")
     * @return a list of {@link ScanReportDto} objects, newest first
     */
    @Operation(summary = "Get all reports for profile", description = "Returns up to 3 cached reports for the given profile, sorted newest first.")
    @GetMapping("/analyse/reports")
    public List<ScanReportDto> getReports(@RequestParam(required = false) String profileName) {
        String accountId = reportService.resolveAccountId(profileName);
        return reportService.getReportsForAccount(accountId);
    }

    /**
     * Compares two cached reports for the same profile, identifying added, removed, and changed resources.
     *
     * <p>GET {@code /analyse/compare?profileName=...&reportA=0&reportB=1}</p>
     *
     * <p>Resources are keyed by composite {@code region::type::id} to avoid collisions. Changes are
     * detected across cost, recommendation, and state dimensions.</p>
     *
     * @param profileName the AWS profile name to look up reports for
     * @param reportA     the index of the older report (0 = newest)
     * @param reportB     the index of the newer report
     * @return 200 with {@code report_a}, {@code report_b} summaries, {@code added}, {@code removed},
     *         {@code changed} lists, and a {@code summary} with counts and cost delta;
     *         or 400 if no reports exist or indices are invalid
     */
    @Operation(summary = "Compare two reports", description = "Compares two cached reports for the same profile. Returns added, removed, and changed resources with cost/state/recommendation diffs.")
    @GetMapping("/analyse/compare")
    public ResponseEntity<Map<String, Object>> compareReports(
            @RequestParam String profileName,
            @RequestParam int reportA,
            @RequestParam int reportB) {
        String accountId = reportService.resolveAccountId(profileName);
        var reports = reportService.getReportsForAccount(accountId);
        if (reports == null || reports.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No reports found for this profile"));
        }
        if (reportA < 0 || reportA >= reports.size() || reportB < 0 || reportB >= reports.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid report index"));
        }
        if (reportA == reportB) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot compare a report with itself"));
        }

        var olderReport = reports.get(reportA);
        var newerReport = reports.get(reportB);
        var allResourcesA = olderReport.getAnalysisResponse() != null ? olderReport.getAnalysisResponse().getResources() : List.<ResourceDto>of();
        var allResourcesB = newerReport.getAnalysisResponse() != null ? newerReport.getAnalysisResponse().getResources() : List.<ResourceDto>of();

        // Detect scan category mismatch
        String catA = olderReport.getAnalysisResponse() != null ? olderReport.getAnalysisResponse().getScanCategory() : null;
        String catB = newerReport.getAnalysisResponse() != null ? newerReport.getAnalysisResponse().getScanCategory() : null;
        boolean categoryMismatch = catA != null && catB != null && !catA.equals(catB);

        // Security-only resource types — excluded when comparing against a cost-only scan
        var securityOnlyTypes = java.util.Set.of(
                "ACM Certificate", "CloudFormation", "CloudTrail", "CloudWatch Alarm",
                "CloudWatch Log Group", "IAM Role", "IAM User", "KMS", "SSM Parameter",
                "Secrets Manager", "Shield Advanced", "Shield Protection", "VPC", "WAF");

        // Filter to comparable resource types if categories differ
        var resourcesA = allResourcesA;
        var resourcesB = allResourcesB;
        var notComparableA = new java.util.ArrayList<ResourceDto>();
        var notComparableB = new java.util.ArrayList<ResourceDto>();
        String categoryWarning = null;

        if (categoryMismatch) {
            String catLabelA = "COST_OPTIMIZATION".equals(catA) ? "Cost & Idle" : "SECURITY_GOVERNANCE".equals(catA) ? "Security" : "Full Scan";
            String catLabelB = "COST_OPTIMIZATION".equals(catB) ? "Cost & Idle" : "SECURITY_GOVERNANCE".equals(catB) ? "Security" : "Full Scan";
            categoryWarning = "Report A was a " + catLabelA + " scan and Report B was a " + catLabelB +
                    " scan. Only comparable resource types are shown. Resources unique to the wider scan are listed separately.";

            // Determine which types are in both scans
            boolean aHasSecurity = "FULL".equals(catA) || "SECURITY_GOVERNANCE".equals(catA);
            boolean bHasSecurity = "FULL".equals(catB) || "SECURITY_GOVERNANCE".equals(catB);
            boolean aHasCost = "FULL".equals(catA) || "COST_OPTIMIZATION".equals(catA);
            boolean bHasCost = "FULL".equals(catB) || "COST_OPTIMIZATION".equals(catB);

            if (aHasSecurity && !bHasSecurity) {
                // A has security types that B doesn't — exclude from comparison, list separately
                notComparableA = allResourcesA.stream().filter(r -> securityOnlyTypes.contains(r.getResourceType())).collect(Collectors.toCollection(ArrayList::new));
                resourcesA = allResourcesA.stream().filter(r -> !securityOnlyTypes.contains(r.getResourceType())).toList();
            }
            if (bHasSecurity && !aHasSecurity) {
                notComparableB = allResourcesB.stream().filter(r -> securityOnlyTypes.contains(r.getResourceType())).collect(Collectors.toCollection(ArrayList::new));
                resourcesB = allResourcesB.stream().filter(r -> !securityOnlyTypes.contains(r.getResourceType())).toList();
            }
            if (aHasCost && !bHasCost) {
                notComparableA = allResourcesA.stream().filter(r -> !securityOnlyTypes.contains(r.getResourceType())).collect(Collectors.toCollection(ArrayList::new));
                resourcesA = allResourcesA.stream().filter(r -> securityOnlyTypes.contains(r.getResourceType())).toList();
            }
            if (bHasCost && !aHasCost) {
                notComparableB = allResourcesB.stream().filter(r -> !securityOnlyTypes.contains(r.getResourceType())).collect(Collectors.toCollection(ArrayList::new));
                resourcesB = allResourcesB.stream().filter(r -> securityOnlyTypes.contains(r.getResourceType())).toList();
            }
        }

        // Build maps by composite key (region + type + id) to avoid collisions
        var mapA = new LinkedHashMap<String, ResourceDto>();
        resourcesA.forEach(r -> mapA.put(resourceKey(r), r));
        var mapB = new LinkedHashMap<String, ResourceDto>();
        resourcesB.forEach(r -> mapB.put(resourceKey(r), r));

        // Added (in B but not A)
        var added = new java.util.ArrayList<Map<String, Object>>();
        // Removed (in A but not B)
        var removed = new java.util.ArrayList<Map<String, Object>>();
        // Changed (in both but different)
        var changed = new java.util.ArrayList<Map<String, Object>>();

        for (var entry : mapB.entrySet()) {
            if (!mapA.containsKey(entry.getKey())) {
                var resource = entry.getValue();
                added.add(Map.of("resource_id", resource.getResourceId(), "resource_type", resource.getResourceType(),
                        "resource_name", resource.getResourceName() != null ? resource.getResourceName() : "",
                        "region", resource.getRegion(), "cost", resource.getMonthlyCostUsd(),
                        "recommendation", resource.getRecommendation() != null ? resource.getRecommendation() : ""));
            }
        }

        for (var entry : mapA.entrySet()) {
            if (!mapB.containsKey(entry.getKey())) {
                var resource = entry.getValue();
                removed.add(Map.of("resource_id", resource.getResourceId(), "resource_type", resource.getResourceType(),
                        "resource_name", resource.getResourceName() != null ? resource.getResourceName() : "",
                        "region", resource.getRegion(), "cost", resource.getMonthlyCostUsd(),
                        "recommendation", resource.getRecommendation() != null ? resource.getRecommendation() : ""));
            }
        }

        for (var entry : mapA.entrySet()) {
            var rB = mapB.get(entry.getKey());
            if (rB != null) {
                var rA = entry.getValue();
                boolean costChanged = Math.abs(rA.getMonthlyCostUsd() - rB.getMonthlyCostUsd()) > 0.01;
                boolean recChanged = !java.util.Objects.equals(rA.getRecommendation(), rB.getRecommendation());
                boolean stateChanged = !java.util.Objects.equals(rA.getState(), rB.getState());
                if (costChanged || recChanged || stateChanged) {
                    var diff = new LinkedHashMap<String, Object>();
                    diff.put("resource_id", rA.getResourceId());
                    diff.put("resource_type", rA.getResourceType());
                    diff.put("resource_name", rA.getResourceName() != null ? rA.getResourceName() : "");
                    diff.put("region", rA.getRegion());
                    if (costChanged) { diff.put("cost_before", rA.getMonthlyCostUsd()); diff.put("cost_after", rB.getMonthlyCostUsd()); }
                    if (recChanged) { diff.put("rec_before", rA.getRecommendation()); diff.put("rec_after", rB.getRecommendation()); }
                    if (stateChanged) { diff.put("state_before", rA.getState()); diff.put("state_after", rB.getState()); }
                    changed.add(diff);
                }
            }
        }

        double costA = resourcesA.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        double costB = resourcesB.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();

        var result = new LinkedHashMap<String, Object>();
        result.put("report_a", Map.of("scanned_at", olderReport.getScannedAt().toString(),
                "total_resources", allResourcesA.size(), "comparable_resources", resourcesA.size(),
                "total_cost", Math.round(costA * 100.0) / 100.0,
                "scan_category", catA != null ? catA : "unknown"));
        result.put("report_b", Map.of("scanned_at", newerReport.getScannedAt().toString(),
                "total_resources", allResourcesB.size(), "comparable_resources", resourcesB.size(),
                "total_cost", Math.round(costB * 100.0) / 100.0,
                "scan_category", catB != null ? catB : "unknown"));
        result.put("added", added);
        result.put("removed", removed);
        result.put("changed", changed);
        result.put("summary", Map.of("added_count", added.size(), "removed_count", removed.size(),
                "changed_count", changed.size(), "cost_change", Math.round((costB - costA) * 100.0) / 100.0));
        if (categoryWarning != null) {
            result.put("category_warning", categoryWarning);
            result.put("not_comparable_a", notComparableA.size());
            result.put("not_comparable_b", notComparableB.size());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Returns the total number of cached reports and a per-account breakdown.
     *
     * <p>GET {@code /analyse/cache/count}</p>
     *
     * @return a map with {@code count} (total) and {@code by_account} (map of account ID to count)
     */
    @Operation(summary = "Get cache report counts", description = "Returns the total number of cached reports and a per-account breakdown.")
    @GetMapping("/analyse/cache/count")
    public Map<String, Object> cacheCount() {
        var counts = reportService.getReportCountsByAccount();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return Map.of("count", total, "by_account", counts);
    }

    /**
     * Deletes all cached scan reports from disk.
     *
     * <p>POST {@code /analyse/cache/clear}</p>
     *
     * @return a map with {@code deleted} count and a summary {@code message}
     */
    @Operation(summary = "Clear all cached reports", description = "Deletes all cached scan reports from disk.")
    @PostMapping("/analyse/cache/clear")
    public Map<String, Object> clearCache() {
        int deleted = reportService.clearAllReports();
        return Map.of("deleted", deleted, "message", "Cleared " + deleted + " cached report(s)");
    }

    @Operation(summary = "Reload demo reports", description = "Reloads pre-baked demo reports from the classpath into the reports directory.")
    @PostMapping("/analyse/demo/reload")
    public Map<String, Object> reloadDemoReports() {
        reportService.loadDemoReports();
        return Map.of("message", "Demo reports reloaded");
    }

    /**
     * Generates a composite key for a resource by combining region, type, and ID with {@code "::"} delimiters.
     * Used for resource deduplication during report comparison.
     *
     * @param r the resource DTO
     * @return a composite key string in the format {@code "region::type::id"}
     */
    private static String resourceKey(ResourceDto r) {
        return (r.getRegion() != null ? r.getRegion() : "") + "::" +
               (r.getResourceType() != null ? r.getResourceType() : "") + "::" +
               (r.getResourceId() != null ? r.getResourceId() : "");
    }
}
