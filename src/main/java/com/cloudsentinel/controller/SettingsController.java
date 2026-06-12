package com.cloudsentinel.controller;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.service.AiSettingsService;
import com.cloudsentinel.service.DocsService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Settings API group, managing AI configuration and documentation reloading.
 *
 * <p>Provides endpoints to read and update AI model settings (models, temperature, top-p, max tokens)
 * at runtime without restart, and to hot-reload documentation YAML files from disk.</p>
 */
@RestController
@Tag(name = "Settings")
public class SettingsController {

    private final AiSettingsService settingsService;
    private final DocsService docsService;

    /**
     * Constructs the settings controller.
     *
     * @param settingsService the AI settings service for reading and updating AI configuration
     * @param docsService     the documentation service for hot-reloading documentation files
     */
    public SettingsController(AiSettingsService settingsService, DocsService docsService) {
        this.settingsService = settingsService;
        this.docsService = docsService;
    }

    /**
     * Returns the current AI configuration including models, temperature, top-p, and max tokens
     * per provider (Ollama and Bedrock).
     *
     * <p>GET {@code /settings/ai}</p>
     *
     * @return a map containing the full AI settings hierarchy
     */
    @Operation(summary = "Get AI settings", description = "Returns current AI configuration including models, temperature, top-p, and max tokens per provider.")
    @GetMapping("/settings/ai")
    public Map<String, Object> getSettings() {
        return settingsService.getAllSettings();
    }

    /**
     * Reloads documentation YAML files from disk without application restart.
     *
     * <p>POST {@code /docs/reload}</p>
     *
     * @return a map with {@code status} ("reloaded") and {@code tabs} (number of documentation tabs loaded)
     */
    @Operation(summary = "Reload documentation", description = "Reloads documentation YAML files from disk without restart.")
    @PostMapping("/docs/reload")
    public Map<String, Object> reloadDocs() {
        docsService.reload();
        return Map.of("status", "reloaded", "tabs", docsService.getTabs().size());
    }

    /**
     * Updates AI configuration at runtime. Changes take effect immediately for new scans;
     * in-progress scans are not affected.
     *
     * <p>POST {@code /settings/ai}</p>
     *
     * @param updates a map of setting keys to new values (e.g. temperature, model name)
     * @return 200 with {@code status} ("updated") and the full current {@code settings} after update
     */
    @Operation(summary = "Update AI settings", description = "Updates AI configuration at runtime. Changes take effect immediately for new scans.")
    @PostMapping("/settings/ai")
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, Object> updates) {
        settingsService.updateSettings(updates);
        return ResponseEntity.ok(Map.of(
                "status", "updated",
                "settings", settingsService.getAllSettings()
        ));
    }
}
