package com.cloudsentinel.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.cloudsentinel.service.AiAnalysisService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the AI API group, providing health checks and status information for
 * the AI providers used by Cloud Resource Sentinel.
 *
 * <p>Supports two AI providers:</p>
 * <ul>
 *   <li><strong>Ollama</strong> (local, open-source) -- queries the Ollama REST API to list
 *       locally available models and check readiness. Tries both IPv4 and IPv6 to handle
 *       Docker binding differences.</li>
 *   <li><strong>AWS Bedrock</strong> (Claude) -- checks availability through the
 *       {@link AiAnalysisService}.</li>
 * </ul>
 *
 * <p>These endpoints are used by the frontend on page load to display AI provider status
 * badges and to populate model selection dropdowns.</p>
 */
@RestController
@Tag(name = "AI")
public class AiStatusController {

    private final AiAnalysisService aiAnalysisService;
    private final String ollamaBaseUrl;

    /**
     * Constructs the AI status controller.
     *
     * @param aiAnalysisService the AI analysis service for checking provider availability and listing models
     * @param ollamaBaseUrl     the Ollama server base URL, configured via {@code spring.ai.ollama.base-url}
     */
    public AiStatusController(AiAnalysisService aiAnalysisService,
                              @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.aiAnalysisService = aiAnalysisService;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    /**
     * Lists all Ollama models that are locally pulled and available for inference.
     * Tries both IPv4 and IPv6 addresses to handle Docker binding differences.
     *
     * <p>GET {@code /ai/ollama/models}</p>
     *
     * @return a map with {@code available} (boolean), {@code models} (list of model details including
     *         name, size, modified_at), {@code model_names} (list of name strings), and on failure:
     *         {@code error} message
     */
    @Operation(summary = "List locally available Ollama models", description = "Queries the Ollama API to list models that are actually pulled and available locally.")
    @GetMapping("/ai/ollama/models")
    public Map<String, Object> ollamaLocalModels() {
        try {
            // Try both IPv6 and IPv4 to handle Docker binding differences
            String json = null;
            for (String host : new String[]{
                    ollamaBaseUrl,
                    ollamaBaseUrl.replace("[::1]", "localhost").replace("127.0.0.1", "localhost"),
                    ollamaBaseUrl.replace("localhost", "[::1]").replace("127.0.0.1", "[::1]")}) {
                try {
                    var url = new java.net.URI(host + "/api/tags").toURL();
                    var conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(5000);
                    String resp = new String(conn.getInputStream().readAllBytes());
                    conn.disconnect();
                    var testRoot = new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp);
                    if (testRoot.path("models").size() > 0) { json = resp; break; }
                    if (json == null) json = resp;
                } catch (Exception ignored) {}
            }
            if (json == null) throw new RuntimeException("Ollama not reachable");
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json);
            var models = new java.util.ArrayList<Map<String, Object>>();
            // Models >= 1.5GB can handle full scans — healthy resources are filtered before
            // sending to AI, keeping prompts under 20K chars even for large accounts.
            long fullScanMinBytes = 1500L * 1024 * 1024; // 1.5 GB
            for (var node : root.path("models")) {
                long size = node.path("size").asLong(0);
                models.add(Map.of(
                        "name", node.path("name").asText(""),
                        "size", size,
                        "modified_at", node.path("modified_at").asText(""),
                        "full_scan_capable", size >= fullScanMinBytes
                ));
            }
            List<String> names = models.stream().map(m -> (String) m.get("name")).toList();
            return Map.of("available", true, "models", models, "model_names", names);
        } catch (Exception e) {
            return Map.of("available", false, "models", List.of(), "model_names", List.of(),
                    "error", "Ollama not reachable: " + e.getMessage());
        }
    }

    /**
     * Returns the warmup/readiness status for all AI providers. Designed to be called on page load
     * to show readiness alerts in the frontend.
     *
     * <p>GET {@code /ai/health}</p>
     *
     * @return a map with {@code ollama_status} (string), {@code ollama_ready} (boolean),
     *         optionally {@code ollama_error}, {@code bedrock_ready} (boolean), and
     *         {@code any_ready} (true if at least one provider is available)
     */
    @Operation(summary = "AI health check", description = "Returns warmup status for AI providers. Check on page load to show alerts.")
    @GetMapping("/ai/health")
    public Map<String, Object> health() {
        String ollamaStatus = aiAnalysisService.getOllamaWarmupStatus();
        String ollamaError = aiAnalysisService.getOllamaWarmupError();
        boolean bedrockReady = aiAnalysisService.isBedrockAvailable();

        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("ollama_status", ollamaStatus);
        result.put("ollama_ready", "ready".equals(ollamaStatus));
        if (ollamaError != null) result.put("ollama_error", ollamaError);
        result.put("bedrock_ready", bedrockReady);
        result.put("any_ready", "ready".equals(ollamaStatus) || bedrockReady);
        return result;
    }

    /**
     * Returns comprehensive availability status for both AI providers (Ollama and Bedrock),
     * including available models per provider and the default provider selection.
     *
     * <p>GET {@code /ai/status}</p>
     *
     * @return a map with {@code providers} (nested Ollama and Bedrock status including available models),
     *         {@code available} (true if any provider is ready), and {@code default_provider} (the
     *         auto-selected provider name); on error: {@code available: false} with {@code error} message
     */
    @Operation(summary = "Check AI provider status", description = "Returns availability status for Ollama (local) and AWS Bedrock (Claude) AI providers, including available models per provider.")
    @GetMapping("/ai/status")
    public Map<String, Object> status() {
        try {
            boolean ollamaAvailable = aiAnalysisService.isOllamaAvailable();
            boolean bedrockAvailable = aiAnalysisService.isBedrockAvailable();

            Map<String, Object> ollama = Map.of(
                    "available", ollamaAvailable,
                    "models", aiAnalysisService.getAllOllamaModels(),
                    "status", ollamaAvailable ? "ready" : "not_available"
            );

            Map<String, Object> bedrock = Map.of(
                    "available", bedrockAvailable,
                    "models", aiAnalysisService.getAllBedrockModels(),
                    "status", bedrockAvailable ? "ready" : "not_available"
            );

            Map<String, Object> providers = Map.of(
                    "ollama", ollama,
                    "bedrock", bedrock
            );

            boolean anyAvailable = ollamaAvailable || bedrockAvailable;
            String defaultProvider = aiAnalysisService.resolveProvider(null);

            return Map.of(
                    "providers", providers,
                    "available", anyAvailable,
                    "default_provider", defaultProvider
            );
        } catch (Exception e) {
            return Map.of("available", false, "error", e.getMessage());
        }
    }
}
