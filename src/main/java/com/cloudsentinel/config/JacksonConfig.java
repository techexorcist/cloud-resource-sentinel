package com.cloudsentinel.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring configuration class for Jackson JSON serialization and deserialization settings.
 *
 * <p>This configuration establishes the global JSON contract for the Cloud Resource Sentinel
 * API by providing a primary {@link ObjectMapper} bean with the following conventions:</p>
 *
 * <ul>
 *   <li><b>snake_case property naming</b>: All JSON field names use {@code snake_case}
 *       (e.g., {@code resource_type}, {@code scan_category}) to align with common REST API
 *       conventions and Python/JavaScript client expectations. This is a deliberate design
 *       decision that applies globally via {@link PropertyNamingStrategies#SNAKE_CASE}.</li>
 *   <li><b>Java Time support</b>: The {@link JavaTimeModule} is registered so that
 *       {@code java.time} types (e.g., {@code Instant}, {@code LocalDateTime}) serialize
 *       as ISO-8601 strings rather than numeric timestamps.</li>
 *   <li><b>Lenient deserialization</b>: Unknown properties in incoming JSON are silently
 *       ignored ({@code FAIL_ON_UNKNOWN_PROPERTIES} disabled), making the API forward-compatible
 *       when new fields are added.</li>
 *   <li><b>Empty bean tolerance</b>: Serialization of empty beans does not throw
 *       ({@code FAIL_ON_EMPTY_BEANS} disabled), which prevents errors when DTOs have
 *       no populated fields.</li>
 * </ul>
 *
 * <p>Note: The {@code snake_case} strategy affects all Spring MVC JSON serialization, including
 * SpringDoc/OpenAPI spec generation. The {@link OpenApiConfig} provides a separate
 * {@code ModelResolver} to avoid schema property names being converted to snake_case.</p>
 *
 * @see OpenApiConfig#modelResolver(ObjectMapper)
 */
@Configuration
public class JacksonConfig {

    /**
     * Creates and configures the primary {@link ObjectMapper} used throughout the application
     * for all JSON serialization and deserialization.
     *
     * <p>This bean is marked {@code @Primary} to ensure it takes precedence over any
     * auto-configured ObjectMapper instances. It configures snake_case naming, Java Time
     * module registration, and lenient serialization/deserialization behavior.</p>
     *
     * @return a fully configured {@link ObjectMapper} with snake_case naming, Java Time support,
     *         and lenient handling of empty beans and unknown properties
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
