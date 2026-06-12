package com.cloudsentinel.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;

/**
 * REST controller providing the root API information endpoint.
 *
 * <p>Serves as a lightweight health-check and version discovery endpoint for clients
 * and monitoring systems.</p>
 */
@RestController
public class RootController {

    /**
     * Returns basic API identification information including the application name and version.
     *
     * <p>GET {@code /api/info}</p>
     *
     * @return a map with {@code message} (API name) and {@code version} (semantic version string)
     */
    @Operation(summary = "API info", description = "Returns the API name and version.")
    @GetMapping("/api/info")
    public Map<String, String> info() {
        return Map.of(
                "message", "Cloud Resource Sentinel API",
                "version", "1.0.0"
        );
    }
}
