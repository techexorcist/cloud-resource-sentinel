package com.cloudsentinel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring configuration class for OpenAPI/Swagger documentation.
 *
 * <p>This configuration customizes the SpringDoc-generated OpenAPI 3.0 specification for
 * the Cloud Resource Sentinel API. It defines the API metadata (title, description, version,
 * contact), the development server URL, and the logical tag groupings used to organize
 * endpoints in the Swagger UI.</p>
 *
 * <p>A key design consideration is the interaction with {@link JacksonConfig}: because the
 * application uses a global {@code SNAKE_CASE} {@link ObjectMapper}, SpringDoc's schema
 * generation would otherwise convert Java field names (e.g., {@code resourceType}) to
 * snake_case in the spec. The {@link #modelResolver(ObjectMapper)} bean overrides this
 * by providing SpringDoc with a vanilla {@link ObjectMapper} that preserves the original
 * Java property names in schema definitions, while the API's actual JSON payloads still
 * use snake_case at runtime.</p>
 *
 * <p>The Swagger UI is accessible at {@code /swagger-ui.html} and the raw OpenAPI spec
 * at {@code /v3/api-docs}.</p>
 *
 * @see JacksonConfig
 * @see OpenApiForwardFilter
 */
@Configuration
public class OpenApiConfig {

    /**
     * Creates a {@link ModelResolver} that uses a default (non-snake_case) {@link ObjectMapper}
     * for SpringDoc schema generation.
     *
     * <p>Without this override, SpringDoc would inherit the application's primary
     * {@code SNAKE_CASE} ObjectMapper, causing all schema property names to be converted
     * to snake_case in the OpenAPI spec. This bean ensures schema definitions reflect
     * the original Java field names (camelCase), while the actual JSON wire format
     * remains snake_case as configured by {@link JacksonConfig}.</p>
     *
     * @param objectMapper the application's primary ObjectMapper (injected but not used;
     *                     a fresh default ObjectMapper is created instead)
     * @return a {@link ModelResolver} backed by a vanilla ObjectMapper
     */
    @Bean
    public ModelResolver modelResolver(ObjectMapper objectMapper) {
        // Use default ObjectMapper for SpringDoc schema generation (no SNAKE_CASE)
        return new ModelResolver(new ObjectMapper());
    }

    /**
     * Defines the OpenAPI 3.0 specification metadata for the Cloud Resource Sentinel API.
     *
     * <p>Configures the API info block (title, description, version, contact), the local
     * development server URL ({@code http://localhost:8000}), and the tag definitions that
     * group related endpoints in the Swagger UI. Tags include Analysis, Reports, Profiles,
     * Pricing, Export, AI, and Audit.</p>
     *
     * @return a fully configured {@link OpenAPI} specification object
     */
    @Bean
    public OpenAPI cloudSentinelOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cloud Resource Sentinel API")
                        .description("""
                                AI-powered AWS account watchdog — cost optimization, security posture, and governance findings.

                                Scans AWS accounts across 30 regions to identify idle resources, security gaps, and governance issues.
                                Classifies findings by type (Cost, Security, Governance) and severity (INFO to CRITICAL),
                                with per-region pricing and context-aware AI recommendations.

                                **Key Features:**
                                - 50 AWS resource type scanners with 39 cross-resource correlation rules
                                - 3 finding types: Cost, Security, Governance — each with severity classification
                                - 3 scan categories: Cost & Idle, Security & Governance, Full
                                - AI analysis via AWS Bedrock (Claude) or Ollama (local) with dual prompt templates
                                - Per-region pricing for all 30 AWS regions (53 AWS SDK modules)
                                - Report caching, comparison with finding-type deltas, and audit trail
                                - Batch scanning across multiple AWS accounts
                                - Read-only by design — 4-layer security guardrail system
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Cloud Resource Sentinel")))
                .servers(List.of(
                        new Server().url("http://localhost:8000").description("Local Development")))
                .tags(List.of(
                        new Tag().name("Analysis").description("Resource scanning and analysis operations"),
                        new Tag().name("Reports").description("Report caching, retrieval, and comparison"),
                        new Tag().name("Profiles").description("AWS profile and region management"),
                        new Tag().name("Pricing").description("Regional pricing data management"),
                        new Tag().name("Export").description("Data export operations"),
                        new Tag().name("AI").description("AI provider status and model management"),
                        new Tag().name("Audit").description("Scan audit trail"),
                        new Tag().name("Settings").description("AI settings and runtime configuration"),
                        new Tag().name("Documentation").description("In-app documentation management"),
                        new Tag().name("Regions").description("AWS region discovery")
                ));
    }
}
