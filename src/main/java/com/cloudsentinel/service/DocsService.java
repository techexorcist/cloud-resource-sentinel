package com.cloudsentinel.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;

/**
 * Loads and serves in-app documentation tabs from YAML files on the classpath.
 *
 * <p>Documentation is organized as a set of YAML files under {@code classpath:docs/},
 * each representing a tab in the documentation view: API Reference, How-To guides,
 * Recommendations, Architecture, and Setup. Each YAML file is parsed into a
 * {@code Map<String, Object>} structure that the Thymeleaf template renders as
 * collapsible sections.</p>
 *
 * <p>Documentation is loaded at startup via {@link #init()} and can be reloaded at
 * runtime via {@link #reload()} without restarting the application.</p>
 */
@Service
public class DocsService {

    private static final Logger log = LoggerFactory.getLogger(DocsService.class);
    /** In-memory list of documentation tabs, each parsed from a YAML file. */
    private final List<Map<String, Object>> tabs = new ArrayList<>();

    /**
     * Loads all documentation YAML files on application startup.
     */
    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * Reloads all documentation YAML files from the classpath, replacing any previously
     * loaded content.
     *
     * <p>Files are loaded in a fixed order: api-reference.yml, how-to.yml, recommendations.yml,
     * architecture.yml, setup.yml. Missing files are silently skipped. Parse errors for
     * individual files are logged as warnings without affecting other files.</p>
     */
    @SuppressWarnings("unchecked")
    public void reload() {
        tabs.clear();
        Yaml yaml = new Yaml();
        String[] files = {"api-reference.yml", "how-to.yml", "recommendations.yml", "architecture.yml", "setup.yml"};
        var resolver = new PathMatchingResourcePatternResolver();
        for (String file : files) {
            try {
                Resource resource = resolver.getResource("classpath:docs/" + file);
                if (resource.exists()) {
                    Map<String, Object> doc = yaml.load(resource.getInputStream());
                    tabs.add(doc);
                    log.info("Loaded doc: {}", file);
                }
            } catch (IOException e) {
                log.warn("Failed to load doc {}: {}", file, e.getMessage());
            }
        }
        log.info("Loaded {} documentation tabs", tabs.size());
    }

    /**
     * Returns the list of documentation tabs currently loaded in memory.
     *
     * <p>Each tab is a parsed YAML document represented as a nested map structure.
     * The list is mutable and reflects the most recent {@link #reload()} call.</p>
     *
     * @return the list of documentation tab maps
     */
    public List<Map<String, Object>> getTabs() {
        return tabs;
    }
}
