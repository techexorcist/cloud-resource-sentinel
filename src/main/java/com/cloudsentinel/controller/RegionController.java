package com.cloudsentinel.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.service.AwsRegionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for listing available AWS regions. Part of the Profiles API group.
 *
 * <p>Provides a single endpoint that returns all 30 supported AWS regions. The region list
 * is sourced from the AWS EC2 API when credentials are available, otherwise falls back to
 * a hardcoded list.</p>
 */
@RestController
@Tag(name = "Profiles")
public class RegionController {

    private final AwsRegionService awsRegionService;

    /**
     * Constructs the region controller.
     *
     * @param awsRegionService the AWS region service for listing available regions
     */
    public RegionController(AwsRegionService awsRegionService) {
        this.awsRegionService = awsRegionService;
    }

    /**
     * Lists all available AWS regions (30 regions).
     *
     * <p>GET {@code /regions}</p>
     *
     * @return a map with a {@code regions} key containing the list of region code strings
     */
    @Operation(summary = "List AWS regions", description = "Returns all available AWS regions (30 regions). Uses the AWS EC2 API if credentials are available, otherwise returns the full hardcoded list.")
    @GetMapping("/regions")
    public Map<String, Object> listRegions() {
        return Map.of("regions", awsRegionService.listRegions());
    }
}
