package com.cloudsentinel.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.AiInsightDto;
import com.cloudsentinel.dto.AiInsightDto.ActionItem;
import com.cloudsentinel.dto.AiInsightDto.ArchitectureInsight;
import com.cloudsentinel.dto.AiInsightDto.RightSizingSuggestion;
import com.cloudsentinel.dto.AiInsightDto.WellArchitected;
import com.cloudsentinel.dto.AiInsightDto.CleanupPhase;
import com.cloudsentinel.dto.AiUsageDto;
import com.cloudsentinel.dto.ResourceDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * AI integration service that generates intelligent insights, prioritized actions, right-sizing
 * suggestions, and architecture recommendations for scanned AWS resources.
 *
 * <p>This service abstracts over multiple AI providers (Ollama for local/open-source models,
 * AWS Bedrock for Claude) via Spring AI's unified {@link ChatModel} interface. The active
 * provider can be swapped by configuration without code changes.</p>
 *
 * <h3>Prompt Construction</h3>
 * <p>The AI prompt is loaded from {@code classpath:prompts/analysis-prompt.txt} at startup
 * and populated with resource data, regional distribution, cost summaries, and sparse region
 * anomalies at runtime. The prompt includes a safety guardrail section instructing the AI
 * to never output executable commands or scripts.</p>
 *
 * <h3>Batching</h3>
 * <p>For large scans spanning many regions, resources are split into batches of up to
 * {@value #MAX_RESOURCES_PER_BATCH} resources per AI call via {@link #generateInsightsBatched}.
 * Results from all batches are merged with deduplication on resource IDs. If a single batch
 * fails, processing continues with remaining batches (partial results are preferred over
 * total failure).</p>
 *
 * <h3>Timeout and Cancellation</h3>
 * <p>Each AI call has a hard timeout of {@value #aiTimeoutSeconds} seconds. The call is
 * submitted to a separate executor and polled every 2 seconds, checking the cancellation
 * flag between polls. This enables responsive cancellation even during a blocking AI call.</p>
 *
 * <h3>Model Warmup</h3>
 * <p>On application startup, the default Ollama model is warmed up in a virtual thread by
 * sending a trivial prompt. This pre-loads the model into memory so the first real analysis
 * does not incur the model loading delay.</p>
 *
 * @see AiSettingsService
 * @see AiInsightDto
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    /** Hard timeout for a single AI call, configurable via {@code ai.timeout-seconds}. */
    @org.springframework.beans.factory.annotation.Value("${ai.timeout-seconds:1200}")
    private long aiTimeoutSeconds;
    /** Maximum resources per AI batch — keeps prompt under ~50K chars (~12K tokens). */
    private static final int MAX_RESOURCES_PER_BATCH = 80;
    /** Maximum prompt size in characters before forcing sub-batching. */
    private static final int MAX_PROMPT_CHARS = 64_000;
    private static final String PROMPT_TEMPLATE_PATH = "prompts/analysis-prompt.txt";
    private static final String SECURITY_PROMPT_TEMPLATE_PATH = "prompts/analysis-security-prompt.txt";

    private final Map<String, ChatModel> chatModels;
    private final AiSettingsService settingsService;
    private final PromptBuilder promptBuilder;

    /**
     * Constructs the AI analysis service with optional Ollama and Bedrock chat models.
     *
     * <p>Both models are optional ({@code required = false}) because the application should
     * still function without AI when neither provider is configured. Available providers
     * are logged on startup for operational visibility.</p>
     *
     * @param ollamaModel     the Ollama chat model bean, or {@code null} if not configured
     * @param bedrockModel    the Bedrock chat model bean, or {@code null} if not configured
     * @param settingsService the AI settings service for model lists and inference parameters
     */
    public AiAnalysisService(
            @Autowired(required = false) @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            @Autowired(required = false) @Qualifier("bedrockProxyChatModel") ChatModel bedrockModel,
            AiSettingsService settingsService
    ) {
        var map = new LinkedHashMap<String, ChatModel>();
        if (ollamaModel != null) map.put("ollama", ollamaModel);
        if (bedrockModel != null) map.put("bedrock", bedrockModel);
        this.chatModels = Map.copyOf(map);
        this.settingsService = settingsService;
        this.promptBuilder = new PromptBuilder(
                loadPromptTemplate(PROMPT_TEMPLATE_PATH),
                loadPromptTemplate(SECURITY_PROMPT_TEMPLATE_PATH));
        log.info("AI providers available: {}", chatModels.keySet());
        log.info("Ollama models: {}", settingsService.getEnabledModels("ollama"));
        log.info("Bedrock models: {}", settingsService.getEnabledModels("bedrock"));
    }

    /**
     * Returns the list of enabled models for the given AI provider.
     *
     * @param provider the provider name (e.g., "ollama", "bedrock")
     * @return the list of enabled model names
     */
    public List<String> getModelsForProvider(String provider) {
        return settingsService.getEnabledModels(provider);
    }

    /** Returns all enabled Ollama model names. @return list of Ollama model names */
    public List<String> getAllOllamaModels() { return settingsService.getEnabledModels("ollama"); }
    /** Returns all enabled Bedrock model names. @return list of Bedrock model names */
    public List<String> getAllBedrockModels() { return settingsService.getEnabledModels("bedrock"); }

    private volatile String ollamaWarmupStatus = "pending";
    private volatile String ollamaWarmupError = null;

    /**
     * Warms up the default Ollama model in a background virtual thread on application startup.
     *
     * <p>Sends a trivial "Say ready" prompt to force model loading into memory. This avoids
     * the first real analysis request incurring a potentially long model load delay.
     * Warmup errors are captured and made available via {@link #getOllamaWarmupStatus()}
     * and {@link #getOllamaWarmupError()} for display in the UI's AI status panel.</p>
     */
    @jakarta.annotation.PostConstruct
    public void warmupModels() {
        if (!chatModels.containsKey("ollama")) {
            ollamaWarmupStatus = "unavailable";
            return;
        }
        // Warm up Ollama default model in background thread with timeout
        Thread.startVirtualThread(() -> {
            String defaultModel = settingsService.getDefaultModel("ollama");
            try {
                ollamaWarmupStatus = "warming";
                log.info("Warming up Ollama model '{}' — this may take a moment on first load...", defaultModel);
                ChatModel model = chatModels.get("ollama");
                var opts = org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .model(defaultModel)
                        .keepAlive("60m")
                        .build();
                // Run with timeout — don't block startup indefinitely
                var warmupExecutor = Executors.newSingleThreadExecutor();
                try {
                    warmupExecutor.submit(() -> model.call(new Prompt("Say ready", opts)))
                            .get(120, TimeUnit.SECONDS);
                    ollamaWarmupStatus = "ready";
                    log.info("Ollama model '{}' warmed up and ready", defaultModel);
                } catch (TimeoutException te) {
                    ollamaWarmupStatus = "error";
                    ollamaWarmupError = "Ollama warmup timed out after 120s. Model may still be loading.";
                    log.warn("Ollama warmup timed out for model '{}'", defaultModel);
                } finally {
                    warmupExecutor.shutdownNow();
                }
            } catch (Exception e) {
                ollamaWarmupStatus = "error";
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("Connection refused") || msg.contains("Connect to")) {
                    ollamaWarmupError = "Ollama is not running. Start with: ollama serve (local) or docker-compose up -d ollama (Docker)";
                } else if (msg.contains("not found") || msg.contains("404")) {
                    ollamaWarmupError = "Model '" + defaultModel + "' not found. Pull it with: ollama pull " + defaultModel;
                } else if (msg.contains("timeout") || msg.contains("Timeout")) {
                    ollamaWarmupError = "Ollama is responding slowly. The model may still be loading — try again in a moment.";
                } else {
                    ollamaWarmupError = "Ollama warmup failed: " + (msg.length() > 100 ? msg.substring(0, 100) + "..." : msg);
                }
                log.warn("Ollama warmup failed: {}", e.getMessage());
            }
        });
    }

    /** Returns the Ollama warmup status: "pending", "warming", "ready", "error", or "unavailable". @return warmup status string */
    public String getOllamaWarmupStatus() { return ollamaWarmupStatus; }
    /** Returns the Ollama warmup error message, or {@code null} if warmup succeeded. @return error message or null */
    public String getOllamaWarmupError() { return ollamaWarmupError; }

    /**
     * Checks whether the Ollama provider is warmed up and ready to accept analysis requests.
     *
     * @return {@code true} if the warmup completed successfully
     */
    public boolean isOllamaAvailable() {
        return "ready".equals(ollamaWarmupStatus);
    }

    /**
     * Checks whether the Bedrock provider is configured and available.
     *
     * @return {@code true} if a Bedrock chat model bean was injected
     */
    public boolean isBedrockAvailable() {
        return chatModels.containsKey("bedrock");
    }

    /**
     * Resolves the actual AI provider to use, with fallback logic.
     *
     * <p>If the preferred provider is available, it is returned. Otherwise, Bedrock is
     * tried first (as it is generally more reliable), then Ollama. If neither is available,
     * the preferred value is returned as-is (which will result in an error downstream).</p>
     *
     * @param preferred the user's preferred provider name, or {@code null} for auto-selection
     * @return the resolved provider name
     */
    public String resolveProvider(String preferred) {
        if (preferred != null && chatModels.containsKey(preferred)) return preferred;
        // Fallback: try bedrock first, then ollama
        if (chatModels.containsKey("bedrock")) return "bedrock";
        if (chatModels.containsKey("ollama")) return "ollama";
        return preferred != null ? preferred : "bedrock";
    }

    /**
     * Convenience overload that generates AI insights without model selection or cancellation.
     *
     * @param resources the list of scanned resources to analyze
     * @param provider  the AI provider to use (e.g., "ollama", "bedrock")
     * @return the generated AI insights
     */
    public AiInsightDto generateInsights(List<ResourceDto> resources, String provider) {
        return generateInsights(resources, provider, null, null);
    }

    /**
     * Convenience overload that generates AI insights with model selection but no cancellation.
     *
     * @param resources the list of scanned resources to analyze
     * @param provider  the AI provider to use
     * @param modelName the specific model to use, or {@code null} for the provider's default
     * @return the generated AI insights
     */
    public AiInsightDto generateInsights(List<ResourceDto> resources, String provider, String modelName) {
        return generateInsights(resources, provider, modelName, null);
    }

    /**
     * Generates AI-powered insights for the given list of scanned resources.
     *
     * <p>This method builds a prompt from the resource data and prompt template, submits it
     * to the resolved AI provider/model, and parses the structured JSON response into an
     * {@link AiInsightDto}. Token usage statistics are captured and included in the result.</p>
     *
     * <p>The AI call runs in a dedicated single-thread executor with a polling loop that checks
     * for cancellation every 2 seconds and enforces a hard timeout of
     * {@value #aiTimeoutSeconds} seconds.</p>
     *
     * @param resources the list of resources to analyze
     * @param provider  the preferred AI provider (resolved via {@link #resolveProvider})
     * @param modelName the specific model to use, or {@code null} for the provider's default
     * @param cancelled optional atomic flag for cooperative cancellation; may be {@code null}
     * @return the parsed AI insights including actions, right-sizing, architecture insights, and usage stats
     * @throws RuntimeException      if the AI call times out, is cancelled, or fails
     * @throws IllegalArgumentException if the specified model is not available for the provider
     */
    public AiInsightDto generateInsights(List<ResourceDto> resources, String provider, String modelName, AtomicBoolean cancelled) {
        String resolvedProvider = resolveProvider(provider);
        ChatModel chatModel = chatModels.get(resolvedProvider);
        if (chatModel == null) {
            log.warn("AI provider '{}' not available (tried: {}), returning empty insights", provider, chatModels.keySet());
            return AiInsightDto.empty();
        }
        provider = resolvedProvider;

        // Resolve and validate model name
        String resolvedModel = modelName;
        if (resolvedModel == null || resolvedModel.isBlank()) {
            List<String> available = getModelsForProvider(provider);
            resolvedModel = available.isEmpty() ? null : available.getFirst();
        } else {
            List<String> available = getModelsForProvider(provider);
            if (!available.contains(resolvedModel)) {
                throw new IllegalArgumentException(
                        "Model '" + resolvedModel + "' is not available for provider '" + provider +
                        "'. Available models: " + available);
            }
        }

        try {
            String prompt = buildInsightPrompt(resources);

            // Guard: if prompt exceeds model context budget, fall back to batched analysis
            if (prompt.length() > MAX_PROMPT_CHARS) {
                log.warn("Prompt size {} chars exceeds budget of {} chars ({} resources). Falling back to batched analysis.",
                        prompt.length(), MAX_PROMPT_CHARS, resources.size());
                return generateInsightsBatched(resources, provider, modelName, cancelled, null);
            }

            double temperature = settingsService.getTemperature(provider);
            double topP = settingsService.getTopP(provider);

            ChatOptions options;
            if ("ollama".equals(provider)) {
                // Use Ollama-specific options to ensure model, keep_alive, and num_ctx reach Ollama API
                var ollamaBuilder = org.springframework.ai.ollama.api.OllamaChatOptions.builder()
                        .temperature(temperature)
                        .topP(topP)
                        .keepAlive("60m")   // prevent model unloading between requests
                        .numCtx(4096);      // context window — balances speed vs capacity
                if (resolvedModel != null) {
                    ollamaBuilder.model(resolvedModel);
                }
                options = ollamaBuilder.build();
            } else {
                var optionsBuilder = ChatOptions.builder()
                        .temperature(temperature)
                        .topP(topP);
                if (resolvedModel != null) {
                    optionsBuilder.model(resolvedModel);
                }
                options = optionsBuilder.build();
            }
            Prompt aiPrompt = new Prompt(prompt, options);
            log.info("Using AI provider '{}' model '{}' (temp={}, top_p={})", provider, resolvedModel, temperature, topP);

            // Run AI call with timeout to prevent indefinite blocking
            ExecutorService aiExecutor = Executors.newSingleThreadExecutor();
            int promptChars = prompt.length();
            long startTimeMs = System.currentTimeMillis();
            Future<ChatResponse> future = aiExecutor.submit(() -> chatModel.call(aiPrompt));
            try {
                ChatResponse chatResponse;
                long elapsed = 0;
                long pollIntervalMs = 2000;
                while (true) {
                    try {
                        chatResponse = future.get(pollIntervalMs, TimeUnit.MILLISECONDS);
                        break; // Got result
                    } catch (TimeoutException te) {
                        elapsed += pollIntervalMs;
                        if (cancelled != null && cancelled.get()) {
                            future.cancel(true);
                            throw new RuntimeException("AI analysis cancelled by user");
                        }
                        if (elapsed >= aiTimeoutSeconds * 1000) {
                            future.cancel(true);
                            throw new RuntimeException(
                                    "AI analysis timed out after " + aiTimeoutSeconds +
                                    " seconds. The model may be overloaded or the prompt too large. " +
                                    "Try selecting fewer regions or using a faster model.");
                        }
                    }
                }
                long durationMs = System.currentTimeMillis() - startTimeMs;
                String response = chatResponse.getResult().getOutput().getText();
                log.debug("AI insight response length: {}", response.length());

                // Extract token usage from ChatResponse metadata
                Integer promptTokens = null;
                Integer completionTokens = null;
                Integer totalTokens = null;
                try {
                    Usage usage = chatResponse.getMetadata().getUsage();
                    if (usage != null) {
                        promptTokens = usage.getPromptTokens();
                        completionTokens = usage.getCompletionTokens();
                        totalTokens = usage.getTotalTokens();
                    }
                } catch (Exception ex) {
                    log.debug("Could not extract token usage: {}", ex.getMessage());
                }

                AiUsageDto aiUsage = AiUsageDto.of(
                        provider, resolvedModel,
                        promptTokens, completionTokens, totalTokens,
                        durationMs, promptChars, response.length()
                );
                log.info("AI usage — provider={}, model={}, prompt_tokens={}, completion_tokens={}, total_tokens={}, duration={}ms, tps={}",
                        provider, resolvedModel, promptTokens, completionTokens, totalTokens, durationMs, aiUsage.tokensPerSecond());

                return parseInsightResponse(response, provider, resolvedModel, aiUsage, resources);
            } catch (ExecutionException e) {
                throw e.getCause() != null ? (Exception) e.getCause() : e;
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new RuntimeException("AI analysis interrupted", e);
            } finally {
                aiExecutor.shutdown();
                try {
                    if (!aiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("AI executor graceful shutdown timed out, forcing termination");
                        aiExecutor.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    aiExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        } catch (IllegalArgumentException e) {
            throw e; // Re-throw validation errors
        } catch (Exception e) {
            log.error("AI insight generation failed with provider '{}' model '{}': {}", provider, resolvedModel, e.getMessage());
            throw new RuntimeException("AI analysis failed with model '" + resolvedModel + "': " + e.getMessage(), e);
        }
    }

    /**
     * Callback interface for receiving progress updates during batched AI analysis.
     */
    public interface BatchProgressCallback {
        /**
         * Called after each batch completes (or before the first batch starts with {@code completedBatches = 0}).
         *
         * @param completedBatches number of batches completed so far
         * @param totalBatches     total number of batches to process
         */
        void onBatchProgress(int completedBatches, int totalBatches);
    }

    /**
     * Filters out healthy resources, batches the remainder by resource count, runs AI analysis
     * per batch, then merges all results into a unified {@link AiInsightDto}.
     *
     * <p>Healthy resources ("Active", "In Use") are excluded since AI adds no value for them.
     * If the filtered list fits in a single prompt, no batching is needed. Otherwise, resources
     * are split into chunks of {@value #MAX_RESOURCES_PER_BATCH} to keep prompts under budget.</p>
     *
     * <p>Batch failures are tolerated: if one batch fails, processing continues with remaining
     * batches. Only if all batches fail is an exception thrown. Partial results are preferred
     * over total failure.</p>
     *
     * @param resources        the full list of scanned resources
     * @param provider         the AI provider to use
     * @param modelName        the specific model, or {@code null} for default
     * @param cancelled        optional cancellation flag
     * @param progressCallback optional callback for batch progress updates
     * @return the merged AI insights from all successful batches
     * @throws RuntimeException if all batches fail or the operation is cancelled
     */
    public AiInsightDto generateInsightsBatched(List<ResourceDto> resources, String provider, String modelName,
                                                 AtomicBoolean cancelled, BatchProgressCallback progressCallback) {
        // Step 1: Filter out healthy resources — AI adds no value for "Active - Good Utilization"
        List<ResourceDto> aiCandidates = resources.stream()
                .filter(r -> {
                    String rec = r.getRecommendation();
                    if (rec == null) return true;
                    return !rec.startsWith("Active") && !rec.equals("In Use");
                })
                .toList();

        log.info("AI analysis: filtered {} → {} resources for AI (excluded {} healthy resources)",
                resources.size(), aiCandidates.size(), resources.size() - aiCandidates.size());

        if (aiCandidates.isEmpty()) {
            log.info("AI analysis: no actionable resources found — skipping AI");
            return new AiInsightDto(
                    "All resources are healthy. No actionable findings detected.",
                    List.of(), List.of(), List.of(), List.of(), List.of(),
                    "No risks identified.", "All resources are operating within normal parameters.",
                    provider, modelName, null);
        }

        // Step 2: If filtered list fits in a single call, skip batching
        String promptCheck = buildInsightPrompt(aiCandidates);
        if (promptCheck.length() <= MAX_PROMPT_CHARS) {
            log.info("AI analysis: {} resources fit in single prompt ({} chars)", aiCandidates.size(), promptCheck.length());
            if (progressCallback != null) progressCallback.onBatchProgress(0, 1);
            AiInsightDto result = generateInsights(aiCandidates, provider, modelName, cancelled);
            if (progressCallback != null) progressCallback.onBatchProgress(1, 1);
            return result;
        }

        // Step 3: Group by region to preserve cross-resource correlation context
        var regionToResources = new LinkedHashMap<String, List<ResourceDto>>();
        for (ResourceDto r : aiCandidates) {
            String region = r.getRegion() != null ? r.getRegion() : "global";
            regionToResources.computeIfAbsent(region, k -> new ArrayList<>()).add(r);
        }

        // Step 4: Pack regions into batches — keep whole regions together, respect resource limit
        List<List<ResourceDto>> batches = new ArrayList<>();
        List<ResourceDto> currentBatch = new ArrayList<>();

        for (var entry : regionToResources.entrySet()) {
            List<ResourceDto> regionResources = entry.getValue();

            if (regionResources.size() > MAX_RESOURCES_PER_BATCH) {
                // Rare: single region exceeds limit — flush current batch, then split the large region
                if (!currentBatch.isEmpty()) {
                    batches.add(currentBatch);
                    currentBatch = new ArrayList<>();
                }
                for (int i = 0; i < regionResources.size(); i += MAX_RESOURCES_PER_BATCH) {
                    batches.add(regionResources.subList(i, Math.min(i + MAX_RESOURCES_PER_BATCH, regionResources.size())));
                }
                log.info("AI analysis: region {} has {} resources — split into {} sub-batches",
                        entry.getKey(), regionResources.size(),
                        (regionResources.size() + MAX_RESOURCES_PER_BATCH - 1) / MAX_RESOURCES_PER_BATCH);
            } else if (currentBatch.size() + regionResources.size() > MAX_RESOURCES_PER_BATCH) {
                // Adding this region would exceed the limit — flush and start new batch
                batches.add(currentBatch);
                currentBatch = new ArrayList<>(regionResources);
            } else {
                // Region fits in current batch
                currentBatch.addAll(regionResources);
            }
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        log.info("AI analysis: {} actionable resources across {} regions → {} batches (max {} per batch)",
                aiCandidates.size(), regionToResources.size(), batches.size(), MAX_RESOURCES_PER_BATCH);

        // Step 5: Run AI on each batch
        List<AiInsightDto> batchResults = new ArrayList<>();
        int totalBatches = batches.size();

        for (int i = 0; i < totalBatches; i++) {
            if (cancelled != null && cancelled.get()) {
                throw new RuntimeException("AI analysis cancelled by user");
            }

            List<ResourceDto> batch = batches.get(i);
            // Summarize which regions are in this batch
            var batchRegions = batch.stream()
                    .map(r -> r.getRegion() != null ? r.getRegion() : "global")
                    .distinct().toList();
            log.info("AI analysis batch {}/{}: {} resources across regions {}",
                    i + 1, totalBatches, batch.size(), batchRegions);

            if (progressCallback != null) progressCallback.onBatchProgress(i, totalBatches);

            try {
                AiInsightDto batchResult = generateInsights(batch, provider, modelName, cancelled);
                batchResults.add(batchResult);
            } catch (Exception e) {
                log.warn("AI analysis batch {}/{} failed: {}", i + 1, totalBatches, e.getMessage());
            }
        }

        if (progressCallback != null) progressCallback.onBatchProgress(totalBatches, totalBatches);

        if (batchResults.isEmpty()) {
            throw new RuntimeException("All AI analysis batches failed");
        }

        if (batchResults.size() == 1) {
            return batchResults.getFirst();
        }

        log.info("Merging {} batch results into unified AI insights", batchResults.size());
        return mergeInsights(batchResults, resources);
    }

    /**
     * Merges multiple batch AI insight results into a single unified {@link AiInsightDto}.
     *
     * <p>Deduplication is performed on prioritized actions and right-sizing suggestions by
     * resource ID. Narrative fields (executive_summary, risk_overview, cost_narrative) are
     * recomputed from the full resource list to avoid per-batch concatenation artifacts.
     * Architecture insights, well-architected findings, and cleanup plans are simply
     * concatenated. Usage statistics are summed across all batches.</p>
     *
     * @param results the list of batch results to merge (must not be empty)
     * @return the merged insight DTO
     */
    private AiInsightDto mergeInsights(List<AiInsightDto> results, List<ResourceDto> allResources) {
        // Recompute narrative fields from the full resource list — avoids per-batch
        // concatenation that produces multiple contradictory paragraphs.
        double totalCost = allResources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
        long idleCount = allResources.stream().filter(ResourceAnalyzer::isActionable).count();
        String executivePreamble = promptBuilder.buildExecutivePreamble(allResources, totalCost, idleCount);

        // For merged results, use only the server-side preamble as the executive summary.
        // Per-batch executive_summaries already contain their own preamble + AI text,
        // which would cause double-preamble if we combined them with the full-list preamble.
        String mergedSummary = executivePreamble;

        // Merge prioritized actions (deduplicate by resourceId)
        var seenResourceIds = new LinkedHashSet<String>();
        List<ActionItem> mergedActions = new ArrayList<>();
        for (AiInsightDto r : results) {
            for (ActionItem a : r.prioritizedActions()) {
                if (seenResourceIds.add(a.resourceId())) {
                    mergedActions.add(a);
                }
            }
        }

        // Merge right-sizing suggestions (deduplicate by resourceId)
        var seenRightSizing = new LinkedHashSet<String>();
        List<RightSizingSuggestion> mergedRightSizing = new ArrayList<>();
        for (AiInsightDto r : results) {
            for (RightSizingSuggestion s : r.rightSizing()) {
                if (seenRightSizing.add(s.resourceId())) {
                    mergedRightSizing.add(s);
                }
            }
        }

        // Deduplicate architecture insights by finding text
        var seenFindings = new LinkedHashSet<String>();
        List<ArchitectureInsight> mergedArchInsights = new ArrayList<>();
        for (AiInsightDto r : results) {
            for (ArchitectureInsight a : r.architectureInsights()) {
                if (seenFindings.add(a.finding())) {
                    mergedArchInsights.add(a);
                }
            }
        }

        // Deduplicate well-architected by finding text
        var seenWellArch = new LinkedHashSet<String>();
        List<WellArchitected> mergedWellArch = new ArrayList<>();
        for (AiInsightDto r : results) {
            for (WellArchitected w : r.wellArchitected()) {
                if (seenWellArch.add(w.finding())) {
                    mergedWellArch.add(w);
                }
            }
        }

        // Cleanup plan: compute from full resource list (server-side, same as narratives)
        List<CleanupPhase> mergedCleanup = promptBuilder.buildCleanupPlan(allResources);

        // Recompute risk_overview and cost_narrative from full resource list
        String mergedRisk = promptBuilder.buildRiskOverview(allResources);
        String mergedCost = promptBuilder.buildCostNarrative(allResources, totalCost, idleCount);

        // Use provider/model from first result
        String provider = results.getFirst().provider();
        String model = results.getFirst().model();

        // Merge usage stats
        AiUsageDto mergedUsage = mergeUsage(results, provider, model);

        return new AiInsightDto(mergedSummary, mergedActions, mergedRightSizing,
                mergedArchInsights, mergedWellArch, mergedCleanup,
                mergedRisk, mergedCost, provider, model, mergedUsage);
    }

    /**
     * Sums AI usage statistics across multiple batch results.
     *
     * @param results  the batch results containing individual usage stats
     * @param provider the AI provider name for the merged result
     * @param model    the model name for the merged result
     * @return the summed usage DTO, or {@code null} if no batch had usage data
     */
    private AiUsageDto mergeUsage(List<AiInsightDto> results, String provider, String model) {
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        int totalTokens = 0;
        long totalDurationMs = 0;
        int totalPromptChars = 0;
        int totalResponseChars = 0;
        boolean hasUsage = false;

        for (AiInsightDto result : results) {
            AiUsageDto usage = result.aiUsage();
            if (usage != null) {
                hasUsage = true;
                if (usage.promptTokens() != null) totalPromptTokens += usage.promptTokens();
                if (usage.completionTokens() != null) totalCompletionTokens += usage.completionTokens();
                if (usage.totalTokens() != null) totalTokens += usage.totalTokens();
                totalDurationMs += usage.durationMs();
                totalPromptChars += usage.promptCharacters();
                totalResponseChars += usage.responseCharacters();
            }
        }

        if (!hasUsage) return null;

        return AiUsageDto.of(provider, model,
                totalPromptTokens > 0 ? totalPromptTokens : null,
                totalCompletionTokens > 0 ? totalCompletionTokens : null,
                totalTokens > 0 ? totalTokens : null,
                totalDurationMs, totalPromptChars, totalResponseChars);
    }

    /**
     * Delegates prompt construction to {@link PromptBuilder}.
     */
    private String buildInsightPrompt(List<ResourceDto> resources) {
        return promptBuilder.build(resources);
    }

    /**
     * Loads the AI prompt template from the classpath at startup.
     *
     * @return the raw prompt template string with placeholder tokens
     * @throws RuntimeException if the template file cannot be loaded
     */
    private String loadPromptTemplate(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream is = resource.getInputStream()) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.info("Loaded AI prompt template from {}", path);
                return template;
            }
        } catch (IOException e) {
            log.error("Failed to load prompt template from {}: {}", path, e.getMessage());
            throw new RuntimeException("Could not load AI prompt template: " + path, e);
        }
    }

    /**
     * Parses the AI's JSON response into a structured {@link AiInsightDto}.
     *
     * <p>Handles markdown code fences ({@code ```json}) and extracts the JSON object
     * between the outermost curly braces. If parsing fails, returns a fallback DTO with
     * a descriptive error message rather than throwing an exception.</p>
     *
     * @param response  the raw AI response text
     * @param provider  the AI provider name for metadata
     * @param modelName the model name for metadata
     * @param aiUsage   the token usage statistics to attach to the result
     * @return the parsed insight DTO, or a fallback DTO with an error message if parsing fails
     */
    private AiInsightDto parseInsightResponse(String response, String provider, String modelName, AiUsageDto aiUsage, List<ResourceDto> resources) {
        try {
            String json = extractJson(response);
            JsonNode root = mapper.readTree(json);

            // Layer 9: Schema validation — checks field patterns, enum values, non-negative numbers
            var validationWarnings = com.cloudsentinel.util.AiOutputValidator.validate(root);
            if (!validationWarnings.isEmpty()) {
                log.warn("AI output schema validation: {} warnings", validationWarnings.size());
            }

            // Pre-compute all statistical narratives server-side — small models hallucinate
            // numbers and sometimes dump raw JSON into narrative fields.
            double totalCost = resources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            long idleCount = resources.stream().filter(ResourceAnalyzer::isActionable).count();
            String riskOverview = promptBuilder.buildRiskOverview(resources);
            String costNarrative = promptBuilder.buildCostNarrative(resources, totalCost, idleCount);

            // Executive summary: server-side stats preamble + sanitized AI narrative
            String executivePreamble = promptBuilder.buildExecutivePreamble(resources, totalCost, idleCount);
            String aiSummary = com.cloudsentinel.util.AiResponseParser.sanitizeAiText(
                    root.path("executive_summary").asText(null));
            String summary = buildExecutiveSummary(executivePreamble, aiSummary);

            List<ActionItem> actions = new ArrayList<>();
            for (JsonNode node : root.path("prioritized_actions")) {
                actions.add(new ActionItem(
                        com.cloudsentinel.util.AiOutputValidator.sanitizeValue(node.path("resource_id").asText(""), "resource_id"),
                        node.path("resource_type").asText(""),
                        node.path("region").asText(""),
                        com.cloudsentinel.util.AiOutputValidator.sanitizeEnum(node.path("action").asText("REVIEW"),
                                com.cloudsentinel.util.AiOutputValidator.getAllowedActions(), "REVIEW"),
                        com.cloudsentinel.util.AiResponseParser.sanitizeAiText(node.path("reasoning").asText("")),
                        com.cloudsentinel.util.AiOutputValidator.sanitizeEnum(node.path("risk").asText("MEDIUM"),
                                com.cloudsentinel.util.AiOutputValidator.getAllowedRisks(), "MEDIUM"),
                        Math.max(0, node.path("estimated_savings").asDouble(0))
                ));
            }

            // Supplement AI actions with server-identified cost-actionable resources the AI missed.
            // This ensures resources contributing to potential_savings always appear in the table.
            var aiResourceIds = actions.stream().map(ActionItem::resourceId).collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (ResourceDto r : resources) {
                if (!ResourceAnalyzer.isActionable(r)) continue;
                if (r.getMonthlyCostUsd() <= 0) continue;
                if (aiResourceIds.contains(r.getResourceId())) continue;
                String action = r.getRecommendation() != null && r.getRecommendation().startsWith("Idle") ? "TERMINATE"
                        : r.getRecommendation() != null && r.getRecommendation().startsWith("Release") ? "TERMINATE"
                        : r.getRecommendation() != null && r.getRecommendation().startsWith("Delete") ? "TERMINATE"
                        : "REVIEW";
                actions.add(new ActionItem(
                        r.getResourceId(), r.getResourceType(), r.getRegion(),
                        action,
                        r.getRecommendation() + (r.getRecommendationDetail() != null && !r.getRecommendationDetail().isBlank()
                                ? " — " + r.getRecommendationDetail() : ""),
                        r.getMonthlyCostUsd() > 50 ? "MEDIUM" : "LOW",
                        r.getMonthlyCostUsd()
                ));
            }

            List<RightSizingSuggestion> rightSizing = new ArrayList<>();
            for (JsonNode node : root.path("right_sizing")) {
                rightSizing.add(new RightSizingSuggestion(
                        node.path("resource_id").asText(""),
                        node.path("current_type").asText(""),
                        node.path("recommended_type").asText(""),
                        node.path("current_cost").asDouble(0),
                        node.path("projected_cost").asDouble(0),
                        node.path("reasoning").asText("")
                ));
            }

            List<ArchitectureInsight> insights = new ArrayList<>();
            for (JsonNode node : root.path("architecture_insights")) {
                insights.add(new ArchitectureInsight(
                        node.path("category").asText("OPTIMIZATION"),
                        node.path("finding").asText(""),
                        node.path("recommendation").asText("")
                ));
            }

            List<WellArchitected> wellArchitected = new ArrayList<>();
            for (JsonNode node : root.path("well_architected")) {
                wellArchitected.add(new WellArchitected(
                        node.path("category").asText("OPERATIONAL"),
                        node.path("finding").asText(""),
                        node.path("detail").asText("")
                ));
            }

            // Cleanup plan: always use server-side computation — the AI consistently
            // returns empty actions fields with small models.
            List<CleanupPhase> cleanupPlan = promptBuilder.buildCleanupPlan(resources);

            String usedModel = modelName != null ? modelName : settingsService.getDefaultModel(provider);
            return new AiInsightDto(summary, actions, rightSizing, insights, wellArchitected, cleanupPlan, riskOverview, costNarrative, provider, usedModel, aiUsage);
        } catch (Exception e) {
            log.warn("AI response was not valid JSON, using server-side preamble. Parse error: {}", e.getMessage());
            String usedModel = modelName != null ? modelName : settingsService.getDefaultModel(provider);
            // Fallback: use server-side computed values for all narrative fields
            double fallbackTotalCost = resources.stream().mapToDouble(ResourceDto::getMonthlyCostUsd).sum();
            long fallbackIdleCount = resources.stream().filter(ResourceAnalyzer::isActionable).count();
            String fallbackPreamble = promptBuilder.buildExecutivePreamble(resources, fallbackTotalCost, fallbackIdleCount);
            String fallbackRiskOverview = promptBuilder.buildRiskOverview(resources);
            String fallbackCostNarrative = promptBuilder.buildCostNarrative(resources, fallbackTotalCost, fallbackIdleCount);
            List<CleanupPhase> fallbackCleanup = promptBuilder.buildCleanupPlan(resources);
            return new AiInsightDto(
                    fallbackPreamble,
                    List.of(), List.of(), List.of(), List.of(), fallbackCleanup,
                    fallbackRiskOverview, fallbackCostNarrative, provider, usedModel, aiUsage);
        }
    }

    /**
     * Combines the server-side executive preamble with the AI's narrative judgment.
     *
     * <p>The preamble provides factual stats (resource counts, costs, top savings) that are
     * always correct. The AI narrative adds interpretive judgment. If the AI's summary is
     * contaminated (contains JSON structures or is empty), only the preamble is used.</p>
     */
    private String buildExecutiveSummary(String preamble, String aiSummary) {
        if (aiSummary == null || aiSummary.isBlank()) {
            return preamble;
        }
        // Reject AI summary if it contains JSON-like structures (model dumped its response)
        if (aiSummary.contains("\"resource_id\"") || aiSummary.contains("\"prioritized_actions\"")
                || aiSummary.contains("\"reasoning\"") || aiSummary.contains("\"estimated_savings\"")) {
            log.warn("AI executive_summary contains raw JSON structures — discarding AI narrative, using server-side preamble only");
            return preamble;
        }
        // Reject if it contains a JSON object (model embedded schema in summary)
        if (aiSummary.contains("{") && aiSummary.contains("}") && aiSummary.indexOf('}') > aiSummary.indexOf('{') + 20) {
            log.warn("AI executive_summary contains embedded JSON object — discarding AI narrative");
            return preamble;
        }
        return preamble + " " + aiSummary;
    }

    /**
     * Extracts a JSON object from the AI response, stripping markdown code fences if present.
     *
     * <p>Handles both {@code ```json} and plain {@code ```} fences. If no JSON object is
     * found, returns the trimmed response as-is for downstream error handling.</p>
     *
     * @param response the raw AI response text
     * @return the extracted JSON string
     */
    private String extractJson(String response) {
        return com.cloudsentinel.util.AiResponseParser.extractJson(response);
    }
}
