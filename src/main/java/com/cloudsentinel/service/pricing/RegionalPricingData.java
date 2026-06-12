package com.cloudsentinel.service.pricing;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * In-memory store for per-region AWS pricing data, loaded from {@code pricing/regional-pricing.json}.
 *
 * <p>This component is the lowest tier of the pricing resolution chain. It holds a parsed
 * {@link JsonNode} tree containing hourly rates for instance-based services (EC2, RDS,
 * ElastiCache, Redshift, OpenSearch, SageMaker) and per-unit rates for service-level pricing
 * (NAT Gateway, S3, EBS, Lambda, etc.) across all 30 supported AWS regions.</p>
 *
 * <h3>Load order</h3>
 * <ol>
 *   <li><strong>Disk file</strong> ({@code ~/.cloud-sentinel/regional-pricing.json}) -- written
 *       by {@link PricingRefreshService} during a pricing refresh. Takes priority because it
 *       may contain fresher data than the classpath bundle.</li>
 *   <li><strong>Classpath</strong> ({@code pricing/regional-pricing.json}) -- the bundled
 *       fallback file shipped with the application JAR.</li>
 * </ol>
 *
 * <p>The {@link #root} field is declared {@code volatile} to ensure safe publication when
 * {@link #loadFromNode(JsonNode)} is called from the {@link PricingRefreshService} on a
 * different thread during hot-reload. No synchronization is needed beyond the volatile write
 * because the entire tree is replaced atomically.</p>
 *
 * @see FallbackPricing
 * @see PricingRefreshService
 */
@Component
public class RegionalPricingData {

    private static final Logger log = LoggerFactory.getLogger(RegionalPricingData.class);
    /** Classpath resource path for the bundled pricing file. */
    private static final String PRICING_FILE = "pricing/regional-pricing.json";
    /** Default region used as a fallback when a requested region is missing from the pricing data. */
    private static final String DEFAULT_REGION = "us-east-1";

    /**
     * The parsed pricing JSON tree. Declared {@code volatile} for safe cross-thread visibility
     * during hot-reload via {@link #loadFromNode(JsonNode)}.
     */
    private volatile JsonNode root;

    /** Path to the disk pricing file, written by {@link PricingRefreshService} during refresh. */
    private static final Path DISK_PRICING_FILE = Path.of(
            System.getProperty("user.home"), ".cloud-sentinel", "regional-pricing.json");

    /**
     * Loads the pricing data on application startup. Tries the disk file first (written by
     * a previous pricing refresh), then falls back to the classpath-bundled file.
     * Called automatically by Spring via {@link PostConstruct}.
     */
    @PostConstruct
    public void load() {
        ObjectMapper mapper = new ObjectMapper();

        // Try disk file first (written by pricing refresh)
        if (Files.exists(DISK_PRICING_FILE)) {
            try (InputStream is = Files.newInputStream(DISK_PRICING_FILE)) {
                root = mapper.readTree(is);
                logLoaded("disk (" + DISK_PRICING_FILE + ")");
                return;
            } catch (Exception e) {
                log.warn("Failed to load disk pricing file, falling back to classpath: {}", e.getMessage());
            }
        }

        // Fall back to classpath bundled file
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(PRICING_FILE)) {
            if (is == null) {
                log.warn("Regional pricing file not found: {}", PRICING_FILE);
                return;
            }
            root = mapper.readTree(is);
            logLoaded("classpath");
        } catch (Exception e) {
            log.error("Failed to load regional pricing data: {}", e.getMessage());
        }
    }

    /**
     * Logs how many service categories were loaded and from which source.
     * Counts top-level fields that do not start with underscore (metadata fields are excluded).
     *
     * @param source a description of where the data was loaded from (e.g. "disk" or "classpath")
     */
    private void logLoaded(String source) {
        int categories = 0;
        var fields = root.fieldNames();
        while (fields.hasNext()) {
            if (!fields.next().startsWith("_")) categories++;
        }
        log.info("Loaded regional pricing data from {} for {} service categories", source, categories);
    }

    /**
     * Hot-reloads pricing data from a new {@link JsonNode} tree, replacing the existing data atomically.
     * Called by {@link PricingRefreshService} after writing fresh pricing data to disk. The volatile
     * write to {@link #root} ensures immediate visibility to all threads reading pricing data.
     *
     * @param newRoot the new pricing JSON tree to use
     */
    public void loadFromNode(JsonNode newRoot) {
        this.root = newRoot;
        log.info("Pricing data hot-reloaded");
    }

    /**
     * Returns the {@code lastVerified} timestamp from the pricing file's metadata section.
     *
     * @return the last verified date string (e.g. {@code "2025-01-15"}), or {@code "unknown"} if unavailable
     */
    public String getLastVerified() {
        if (root == null) return "unknown";
        return root.path("_metadata").path("lastVerified").asText("unknown");
    }

    /**
     * Gets the hourly rate for a specific instance type in an instance-based service
     * (ec2, rds, elasticache, redshift, opensearch, sagemaker).
     * Falls back to {@code us-east-1} if the requested region is not present in the data.
     *
     * @param service      the service category key (e.g. {@code "ec2"}, {@code "rds"}, {@code "elasticache"})
     * @param region       the AWS region code (e.g. {@code "us-east-1"})
     * @param instanceType the instance type to look up (e.g. {@code "m5.large"}, {@code "db.t3.micro"})
     * @return the hourly rate in USD, or {@code -1} if not found
     */
    public double getInstanceRate(String service, String region, String instanceType) {
        if (root == null) return -1;

        JsonNode regionNode = root.path(service).path(region);
        if (regionNode.isMissingNode()) regionNode = root.path(service).path(DEFAULT_REGION);
        if (regionNode.isMissingNode()) return -1;

        JsonNode val = regionNode.path(instanceType);
        return val.isNumber() ? val.asDouble() : -1;
    }

    /**
     * Gets the base "large" hourly rate for a service in a region, stored as the
     * {@code _baseLargeHourly} field in the pricing JSON. Used by {@link FallbackPricing}
     * to estimate costs for instance types not explicitly listed, by applying a
     * {@link FallbackPricing#sizeMultiplier(String) size multiplier}.
     * Falls back to {@code us-east-1} if the requested region is not present.
     *
     * @param service the service category key (e.g. {@code "ec2"}, {@code "rds"})
     * @param region  the AWS region code
     * @return the base large hourly rate in USD, or {@code -1} if not found
     */
    public double getBaseLargeHourly(String service, String region) {
        if (root == null) return -1;

        JsonNode regionNode = root.path(service).path(region);
        if (regionNode.isMissingNode()) regionNode = root.path(service).path(DEFAULT_REGION);
        if (regionNode.isMissingNode()) return -1;

        JsonNode val = regionNode.path("_baseLargeHourly");
        return val.isNumber() ? val.asDouble() : -1;
    }

    /**
     * Gets a service-level rate from the {@code "services"} section of the pricing JSON.
     * Service-level rates cover non-instance resources such as NAT Gateways, S3 per-GB,
     * EBS per-GB, Lambda per-request, etc. Falls back to {@code us-east-1} if the
     * requested region or key is missing.
     *
     * @param region  the AWS region code (e.g. {@code "us-east-1"})
     * @param rateKey the rate key (e.g. {@code "natGatewayHourly"}, {@code "s3PerGb"}, {@code "ebsGp3PerGb"})
     * @return the rate value, or {@code -1} if not found in either the requested region or the default region
     */
    public double getServiceRate(String region, String rateKey) {
        if (root == null) return -1;

        JsonNode regionNode = root.path("services").path(region);
        if (!regionNode.isMissingNode()) {
            JsonNode val = regionNode.path(rateKey);
            if (val.isNumber()) return val.asDouble();
        }

        // Fall back to us-east-1 for missing keys
        JsonNode defaultNode = root.path("services").path(DEFAULT_REGION);
        if (!defaultNode.isMissingNode()) {
            JsonNode val = defaultNode.path(rateKey);
            if (val.isNumber()) return val.asDouble();
        }
        return -1;
    }

    /**
     * Exports all pricing data as CSV text with columns: service, region, type, hourly_rate_usd.
     * Instance services (ec2, rds, etc.) produce one row per region × instance type.
     * Service-level rates produce rows with the rate key as the type column.
     *
     * @return CSV string including header row
     */
    public String exportCsv() {
        if (root == null) return "service,region,type,hourly_rate_usd\n";
        var sb = new StringBuilder("service,region,type,hourly_rate_usd\n");
        String[] instanceServices = {"ec2", "rds", "elasticache", "redshift", "opensearch", "sagemaker"};
        for (String svc : instanceServices) {
            var svcNode = root.path(svc);
            if (svcNode.isMissingNode()) continue;
            svcNode.fieldNames().forEachRemaining(region -> {
                var regionNode = svcNode.path(region);
                regionNode.fieldNames().forEachRemaining(type -> {
                    var val = regionNode.path(type);
                    if (val.isNumber()) {
                        sb.append(csvEscape(svc)).append(',')
                          .append(csvEscape(region)).append(',')
                          .append(csvEscape(type)).append(',')
                          .append(val.asDouble()).append('\n');
                    }
                });
            });
        }
        var servicesNode = root.path("services");
        if (!servicesNode.isMissingNode()) {
            servicesNode.fieldNames().forEachRemaining(region -> {
                var regionNode = servicesNode.path(region);
                regionNode.fieldNames().forEachRemaining(rateKey -> {
                    var val = regionNode.path(rateKey);
                    if (val.isNumber()) {
                        sb.append("services,")
                          .append(csvEscape(region)).append(',')
                          .append(csvEscape(rateKey)).append(',')
                          .append(val.asDouble()).append('\n');
                    }
                });
            });
        }
        return sb.toString();
    }

    /**
     * Exports the raw pricing JSON as bytes for download.
     *
     * @return the pricing JSON as a UTF-8 byte array, or an empty JSON object if no data is loaded
     */
    public byte[] exportJson() {
        if (root == null) return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root);
        } catch (Exception e) {
            log.error("Failed to export pricing JSON: {}", e.getMessage());
            return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
