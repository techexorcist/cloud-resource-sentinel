package com.cloudsentinel.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.service.AwsProfileService;
import com.cloudsentinel.service.pricing.PricingRefreshService;
import com.cloudsentinel.service.pricing.RegionalPricingData;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Pricing API group, providing pricing data status, progress monitoring,
 * and on-demand refresh of AWS pricing rates.
 *
 * <p>The refresh operation fetches live on-demand pricing from the AWS Pricing API for EC2, RDS,
 * ElastiCache, Redshift, OpenSearch, and SageMaker across all 30 supported regions, then
 * hot-reloads the data in memory without requiring a restart.</p>
 *
 * @see PricingRefreshService
 * @see RegionalPricingData
 */
@RestController
@Tag(name = "Pricing")
public class PricingController {

    private final PricingRefreshService refreshService;
    private final RegionalPricingData pricingData;
    private final AwsProfileService profileService;

    /**
     * Constructs the pricing controller with its required service dependencies.
     *
     * @param refreshService the pricing refresh service for triggering and monitoring refresh operations
     * @param pricingData    the regional pricing data for querying last-verified dates
     * @param profileService the AWS profile service for listing available profiles during refresh
     */
    public PricingController(PricingRefreshService refreshService, RegionalPricingData pricingData, AwsProfileService profileService) {
        this.refreshService = refreshService;
        this.pricingData = pricingData;
        this.profileService = profileService;
    }

    /**
     * Returns the current pricing data status, including the last verified date and whether
     * a refresh is currently in progress.
     *
     * <p>GET {@code /pricing/status}</p>
     *
     * @return a map with {@code lastVerified} (date string) and {@code refreshing} (boolean)
     */
    @Operation(summary = "Get pricing status", description = "Returns the last verified date of the pricing data and whether a refresh is in progress.")
    @GetMapping("/pricing/status")
    public Map<String, Object> pricingStatus() {
        return Map.of(
                "lastVerified", pricingData.getLastVerified(),
                "refreshing", refreshService.isRefreshing()
        );
    }

    /**
     * Returns the current progress of an ongoing pricing refresh operation. Designed for
     * frontend polling to show a progress bar.
     *
     * <p>GET {@code /pricing/progress}</p>
     *
     * @return a map with {@code refreshing} (boolean), {@code message} (current step), and {@code percent} (0-100)
     */
    @Operation(summary = "Get pricing refresh progress", description = "Returns the current progress of a pricing refresh operation, including step message and percentage.")
    @GetMapping("/pricing/progress")
    public Map<String, Object> pricingProgress() {
        return refreshService.getProgress();
    }

    /**
     * Triggers a pricing refresh from the AWS Pricing API for all 30 regions. Tries each
     * available AWS profile until one authenticates successfully. On completion, the pricing
     * file is updated on disk and the in-memory data is hot-reloaded without restart.
     *
     * <p>POST {@code /pricing/refresh}</p>
     *
     * @return a result map with {@code status} ("success", "error", or "already_running"),
     *         {@code message}, and on success: {@code updated}, {@code failed}, {@code lastVerified}
     */
    @Operation(summary = "Download pricing data as CSV", description = "Exports all regional pricing data as a CSV file with columns: service, region, type, hourly_rate_usd.")
    @GetMapping(value = "/pricing/download", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> downloadPricingCsv() {
        String csv = pricingData.exportCsv();
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=aws-pricing-" + pricingData.getLastVerified() + ".csv")
                .header("Content-Type", "text/csv; charset=UTF-8")
                .body(csv);
    }

    @Operation(summary = "Download pricing data as JSON", description = "Exports the raw regional pricing JSON file.")
    @GetMapping(value = "/pricing/download/json", produces = "application/json")
    public org.springframework.http.ResponseEntity<byte[]> downloadPricingJson() {
        byte[] json = pricingData.exportJson();
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=aws-pricing-" + pricingData.getLastVerified() + ".json")
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(json);
    }

    @Operation(summary = "Refresh pricing from AWS", description = "Fetches live pricing from the AWS Pricing API for all 30 regions. Tries each AWS profile until one authenticates. Updates the pricing file and hot-reloads in-memory data without restart.")
    @PostMapping("/pricing/refresh")
    public Map<String, Object> refreshPricing() {
        var profiles = profileService.listProfiles();
        if (profiles.isEmpty()) {
            profiles = java.util.List.of("");
        }
        return refreshService.refresh(profiles);
    }
}
