package com.cloudsentinel.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages AI provider configuration settings at runtime, including model lists, default models,
 * temperature, top-p, and max tokens for both Ollama and Bedrock providers.
 *
 * <p>Settings are initialized from Spring {@code @Value} properties on construction and stored
 * in a {@link ConcurrentHashMap} for thread-safe read/write access. Settings can be updated
 * at runtime via {@link #updateSettings(Map)} without requiring an application restart.</p>
 *
 * <p>The settings map uses a flat key convention: {@code provider.setting} (e.g.,
 * {@code "ollama.temperature"}, {@code "bedrock.models"}).</p>
 *
 * @see AiAnalysisService
 */
@Service
public class AiSettingsService {

    /** Thread-safe settings map keyed by {@code provider.setting} (e.g., "ollama.temperature"). */
    private final Map<String, Object> settings = new ConcurrentHashMap<>();

    /**
     * Constructs the AI settings service with initial values from Spring configuration properties.
     *
     * <p>Model lists are parsed from comma-separated strings. All settings are stored in a
     * mutable map that can be updated at runtime via {@link #updateSettings(Map)}.</p>
     *
     * @param ollamaModels    comma-separated list of enabled Ollama models
     * @param bedrockModels   comma-separated list of enabled Bedrock models
     * @param ollamaDefault   default Ollama model name
     * @param bedrockDefault  default Bedrock model name
     * @param ollamaTemp      Ollama inference temperature (0.0-1.0)
     * @param ollamaTopP      Ollama top-p sampling parameter
     * @param bedrockTemp     Bedrock inference temperature
     * @param bedrockMaxTokens Bedrock maximum response token limit
     */
    public AiSettingsService(
            @Value("${ai.models.ollama:qwen2.5:3b}") String ollamaModels,
            @Value("${ai.models.bedrock:us.anthropic.claude-sonnet-4-6}") String bedrockModels,
            @Value("${ai.models.ollama.default:qwen2.5:3b}") String ollamaDefault,
            @Value("${ai.models.bedrock.default:us.anthropic.claude-sonnet-4-6}") String bedrockDefault,
            @Value("${spring.ai.ollama.chat.options.temperature:0.3}") double ollamaTemp,
            @Value("${spring.ai.ollama.chat.options.top-p:0.9}") double ollamaTopP,
            @Value("${spring.ai.ollama.chat.options.num-predict:4096}") int ollamaMaxTokens,
            @Value("${spring.ai.bedrock.converse.chat.options.temperature:0.3}") double bedrockTemp,
            @Value("${spring.ai.bedrock.converse.chat.options.top-p:0.9}") double bedrockTopP,
            @Value("${spring.ai.bedrock.converse.chat.options.max-tokens:4096}") int bedrockMaxTokens
    ) {
        settings.put("ollama.models", new ArrayList<>(parseList(ollamaModels)));
        settings.put("bedrock.models", new ArrayList<>(parseList(bedrockModels)));
        settings.put("ollama.default", ollamaDefault);
        settings.put("bedrock.default", bedrockDefault);
        settings.put("ollama.temperature", ollamaTemp);
        settings.put("ollama.top_p", ollamaTopP);
        settings.put("ollama.max_tokens", ollamaMaxTokens);
        settings.put("bedrock.temperature", bedrockTemp);
        settings.put("bedrock.top_p", bedrockTopP);
        settings.put("bedrock.max_tokens", bedrockMaxTokens);
    }

    /**
     * Parses a comma-separated string into a list of trimmed, non-empty strings.
     *
     * @param csv the comma-separated input string
     * @return an immutable list of parsed values
     */
    private List<String> parseList(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Returns the list of enabled models for the given AI provider.
     *
     * @param provider the provider name (e.g., "ollama", "bedrock")
     * @return the list of model names, or an empty list if the provider is not configured
     */
    @SuppressWarnings("unchecked")
    public List<String> getEnabledModels(String provider) {
        return (List<String>) settings.getOrDefault(provider + ".models", List.of());
    }

    /**
     * Returns the default model name for the given provider.
     *
     * @param provider the provider name
     * @return the default model name, or an empty string if not configured
     */
    public String getDefaultModel(String provider) {
        return (String) settings.getOrDefault(provider + ".default", "");
    }

    /**
     * Returns the inference temperature for the given provider.
     *
     * @param provider the provider name
     * @return the temperature value (defaults to 0.3 if not configured)
     */
    public double getTemperature(String provider) {
        return (double) settings.getOrDefault(provider + ".temperature", 0.3);
    }

    /**
     * Returns the top-p (nucleus sampling) parameter for the given provider.
     *
     * @param provider the provider name
     * @return the top-p value (defaults to 0.9 if not configured)
     */
    public double getTopP(String provider) {
        return (double) settings.getOrDefault(provider + ".top_p", 0.9);
    }

    /**
     * Returns the maximum response token limit for the given provider.
     *
     * @param provider the provider name
     * @return the max tokens value (defaults to 4096 if not configured)
     */
    public int getMaxTokens(String provider) {
        return (int) settings.getOrDefault(provider + ".max_tokens", 4096);
    }

    /**
     * Returns an unmodifiable snapshot of all current settings.
     *
     * @return a copy of the settings map
     */
    public Map<String, Object> getAllSettings() {
        return Map.copyOf(settings);
    }

    /**
     * Updates settings at runtime from a map of key-value pairs.
     *
     * <p>Only existing keys are updated; unknown keys are silently ignored. Numeric values
     * are coerced to match the original type (int or double) to maintain type consistency.
     * List values are defensively copied.</p>
     *
     * @param updates a map of setting keys to new values
     */
    @SuppressWarnings("unchecked")
    public void updateSettings(Map<String, Object> updates) {
        for (var entry : updates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (settings.containsKey(key)) {
                if (value instanceof List<?> list) {
                    settings.put(key, new ArrayList<>((List<String>) list));
                } else if (value instanceof Number num) {
                    // Preserve original type
                    if (settings.get(key) instanceof Integer) {
                        settings.put(key, num.intValue());
                    } else {
                        settings.put(key, num.doubleValue());
                    }
                } else {
                    settings.put(key, value);
                }
            }
        }
    }
}
