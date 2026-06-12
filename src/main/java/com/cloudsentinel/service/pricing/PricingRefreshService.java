package com.cloudsentinel.service.pricing;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

/**
 * Service responsible for refreshing the regional pricing data file from the live AWS Pricing API.
 *
 * <p>The refresh flow proceeds as follows:</p>
 * <ol>
 *   <li><strong>Authentication</strong> -- iterates through the provided AWS profiles (including SSO profiles)
 *       and attempts to authenticate with the Pricing API using each one until one succeeds.</li>
 *   <li><strong>Fetch rates</strong> -- queries the AWS Pricing API for on-demand hourly rates across all
 *       30 supported regions for six service categories:
 *       EC2 (11 types), RDS (5 types), ElastiCache (4 types), Redshift (2 types),
 *       OpenSearch (6 types), and SageMaker (5 types).</li>
 *   <li><strong>Preserve services section</strong> -- copies the existing {@code "services"} section
 *       (NAT Gateway, S3, EBS, Lambda, etc.) from the previous pricing file because those rates
 *       are simpler per-unit charges that rarely change and require more complex API queries.</li>
 *   <li><strong>Write to disk</strong> -- serializes the new pricing data to
 *       {@code ~/.cloud-sentinel/regional-pricing.json} with pretty-printing.</li>
 *   <li><strong>Hot-reload</strong> -- calls {@link RegionalPricingData#loadFromNode(JsonNode)} to update
 *       the in-memory pricing data without requiring a restart.</li>
 *   <li><strong>Clear cache</strong> -- evicts all entries from the Spring {@code "pricing"} cache
 *       so that subsequent lookups via {@link PricingService} use the fresh data.</li>
 * </ol>
 *
 * <p>Only one refresh operation can run at a time, enforced by an {@link AtomicBoolean} guard.
 * Progress is tracked via volatile fields that can be polled by the frontend.</p>
 *
 * @see PricingService
 * @see RegionalPricingData
 */
@Service
public class PricingRefreshService {

    private static final Logger log = LoggerFactory.getLogger(PricingRefreshService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final double HOURS_PER_MONTH = 730.0;

    private final RegionalPricingData pricingData;
    private final org.springframework.cache.CacheManager cacheManager;
    /** Guard that ensures only one refresh operation runs at a time. */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile String progressMessage = "";
    private volatile int progressPercent = 0;

    /**
     * Maps AWS region codes to human-readable location names required by the Pricing API filter.
     * Covers all 30 supported regions.
     */
    private static final Map<String, String> REGION_LOCATION = Map.ofEntries(
            Map.entry("us-east-1", "US East (N. Virginia)"),
            Map.entry("us-east-2", "US East (Ohio)"),
            Map.entry("us-west-1", "US West (N. California)"),
            Map.entry("us-west-2", "US West (Oregon)"),
            Map.entry("af-south-1", "Africa (Cape Town)"),
            Map.entry("ap-east-1", "Asia Pacific (Hong Kong)"),
            Map.entry("ap-south-1", "Asia Pacific (Mumbai)"),
            Map.entry("ap-south-2", "Asia Pacific (Hyderabad)"),
            Map.entry("ap-southeast-1", "Asia Pacific (Singapore)"),
            Map.entry("ap-southeast-2", "Asia Pacific (Sydney)"),
            Map.entry("ap-southeast-3", "Asia Pacific (Jakarta)"),
            Map.entry("ap-southeast-4", "Asia Pacific (Melbourne)"),
            Map.entry("ap-southeast-5", "Asia Pacific (Malaysia)"),
            Map.entry("ap-northeast-1", "Asia Pacific (Tokyo)"),
            Map.entry("ap-northeast-2", "Asia Pacific (Seoul)"),
            Map.entry("ap-northeast-3", "Asia Pacific (Osaka)"),
            Map.entry("ca-central-1", "Canada (Central)"),
            Map.entry("ca-west-1", "Canada West (Calgary)"),
            Map.entry("eu-central-1", "EU (Frankfurt)"),
            Map.entry("eu-central-2", "EU (Zurich)"),
            Map.entry("eu-west-1", "EU (Ireland)"),
            Map.entry("eu-west-2", "EU (London)"),
            Map.entry("eu-west-3", "EU (Paris)"),
            Map.entry("eu-south-1", "EU (Milan)"),
            Map.entry("eu-south-2", "EU (Spain)"),
            Map.entry("eu-north-1", "EU (Stockholm)"),
            Map.entry("il-central-1", "Israel (Tel Aviv)"),
            Map.entry("me-south-1", "Middle East (Bahrain)"),
            Map.entry("me-central-1", "Middle East (UAE)"),
            Map.entry("sa-east-1", "South America (Sao Paulo)")
    );

    /** Representative EC2 instance types to fetch pricing for during refresh. */
    private static final List<String> EC2_TYPES = List.of(
            "t3.nano", "t3.micro", "t3.small", "t3.medium", "t3.large",
            "m5.large", "m6i.large", "c5.large", "c6i.large", "r5.large", "r6i.large"
    );
    /** Representative RDS instance types to fetch pricing for during refresh. */
    private static final List<String> RDS_TYPES = List.of(
            "db.t3.micro", "db.t3.small", "db.t3.medium", "db.m5.large", "db.r5.large"
    );
    /** Representative ElastiCache node types to fetch pricing for during refresh. */
    private static final List<String> CACHE_TYPES = List.of(
            "cache.t3.micro", "cache.t3.small", "cache.m5.large", "cache.r5.large"
    );
    /** Representative Redshift node types to fetch pricing for during refresh. */
    private static final List<String> REDSHIFT_TYPES = List.of("dc2.large", "ra3.xlplus");
    /** Representative OpenSearch instance types to fetch pricing for during refresh. */
    private static final List<String> OPENSEARCH_TYPES = List.of(
            "m5.large.search", "m5.xlarge.search", "r5.large.search", "r5.xlarge.search",
            "t3.small.search", "t3.medium.search"
    );
    /** Representative SageMaker instance types to fetch pricing for during refresh. */
    private static final List<String> SAGEMAKER_TYPES = List.of(
            "ml.t3.medium", "ml.t3.large", "ml.t3.xlarge", "ml.m5.large", "ml.m5.xlarge"
    );

    /**
     * Constructs the pricing refresh service.
     *
     * @param pricingData  the in-memory regional pricing data to hot-reload after refresh
     * @param cacheManager the Spring cache manager for clearing the "pricing" cache
     */
    public PricingRefreshService(RegionalPricingData pricingData, org.springframework.cache.CacheManager cacheManager) {
        this.pricingData = pricingData;
        this.cacheManager = cacheManager;
    }

    /**
     * Checks whether a pricing refresh operation is currently in progress.
     *
     * @return {@code true} if a refresh is running, {@code false} otherwise
     */
    public boolean isRefreshing() {
        return refreshing.get();
    }

    /**
     * Returns the current progress of the refresh operation for frontend polling.
     *
     * @return a map containing {@code "refreshing"} (boolean), {@code "message"} (current step description),
     *         and {@code "percent"} (0-100 progress)
     */
    public Map<String, Object> getProgress() {
        return Map.of(
                "refreshing", refreshing.get(),
                "message", progressMessage,
                "percent", progressPercent
        );
    }

    /**
     * Executes the full pricing refresh workflow. Only one refresh can run at a time; if already
     * running, returns immediately with an {@code "already_running"} status.
     *
     * <p>The method iterates through the provided AWS profiles to find one with valid credentials,
     * fetches EC2/RDS/ElastiCache/Redshift/OpenSearch/SageMaker rates across all 30 regions,
     * preserves the existing services section, writes to disk, hot-reloads in memory, and clears
     * the Spring pricing cache.</p>
     *
     * @param profiles the list of AWS profile names to attempt authentication with (tried in order)
     * @return a result map with {@code "status"} ({@code "success"}, {@code "error"}, or
     *         {@code "already_running"}), {@code "message"}, and on success: {@code "updated"},
     *         {@code "failed"}, and {@code "lastVerified"} fields
     */
    public Map<String, Object> refresh(List<String> profiles) {
        if (!refreshing.compareAndSet(false, true)) {
            return Map.of("status", "already_running", "message", "Pricing refresh is already in progress");
        }

        // Try each profile until one works
        PricingClient client = null;
        String usedProfile = null;
        var triedProfiles = new java.util.ArrayList<String>();

        for (String profile : profiles) {
            String name = (profile == null || profile.isBlank()) ? "default" : profile;
            triedProfiles.add(name);
            try {
                PricingClient candidate = ReadOnlyAwsClientFactory.build(
                        PricingClient.builder(), buildCredentials(profile), Region.US_EAST_1);
                candidate.describeServices(b -> b.serviceCode("AmazonEC2").maxResults(1));
                client = candidate;
                usedProfile = name;
                log.info("Pricing refresh authenticated with profile: {}", name);
                break;
            } catch (Exception e) {
                log.debug("Profile '{}' failed for pricing: {}", name, e.getMessage());
            }
        }

        if (client == null) {
            refreshing.set(false);
            return Map.of("status", "error",
                    "message", "All AWS profiles failed authentication. Tried: " + String.join(", ", triedProfiles) + ". Run 'aws sso login --profile <profile>' to refresh credentials.");
        }

        int updated = 0;
        int failed = 0;

        try {
            progressMessage = "Authenticated with " + usedProfile;
            progressPercent = 5;
            log.info("Starting pricing refresh from AWS Pricing API using profile '{}'", usedProfile);

            ObjectNode newRoot = mapper.createObjectNode();

            // Metadata
            String today = LocalDate.now().toString();
            ObjectNode meta = newRoot.putObject("_metadata");
            meta.put("description", "AWS on-demand pricing by region. Auto-refreshed from AWS Pricing API.");
            meta.put("lastVerified", today);
            meta.put("source", "AWS Pricing API (auto-refresh)");
            ObjectNode fetchedAt = meta.putObject("ratesFetchedAt");
            fetchedAt.put("ec2", today + " (AWS Pricing API)");
            fetchedAt.put("rds", today + " (AWS Pricing API)");
            fetchedAt.put("elasticache", today + " (AWS Pricing API)");
            fetchedAt.put("redshift", today + " (AWS Pricing API)");
            fetchedAt.put("opensearch", today + " (AWS Pricing API)");
            fetchedAt.put("sagemaker", today + " (AWS Pricing API)");
            fetchedAt.put("services", "Carried from previous file (service-level rates not auto-refreshed)");

            // EC2
            progressMessage = "Fetching EC2 pricing...";
            progressPercent = 10;
            ObjectNode ec2Node = newRoot.putObject("ec2");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = ec2Node.putObject(regionEntry.getKey());
                double baseLarge = -1;
                for (String type : EC2_TYPES) {
                    try {
                        double hourly = fetchEc2Hourly(client, type, regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            if (type.equals("m5.large")) baseLarge = hourly;
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                regionNode.put("_baseLargeHourly", baseLarge > 0 ? baseLarge : 0.096);
            }

            // RDS
            progressMessage = "Fetching RDS pricing...";
            progressPercent = 35;
            ObjectNode rdsNode = newRoot.putObject("rds");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = rdsNode.putObject(regionEntry.getKey());
                double baseLarge = -1;
                for (String type : RDS_TYPES) {
                    try {
                        double hourly = fetchRdsHourly(client, type, regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            if (type.equals("db.m5.large")) baseLarge = hourly;
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                regionNode.put("_baseLargeHourly", baseLarge > 0 ? baseLarge : 0.171);
            }

            // ElastiCache
            progressMessage = "Fetching ElastiCache pricing...";
            progressPercent = 55;
            ObjectNode cacheNode = newRoot.putObject("elasticache");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = cacheNode.putObject(regionEntry.getKey());
                double baseLarge = -1;
                for (String type : CACHE_TYPES) {
                    try {
                        double hourly = fetchElastiCacheHourly(client, type, regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            if (type.equals("cache.m5.large")) baseLarge = hourly;
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                regionNode.put("_baseLargeHourly", baseLarge > 0 ? baseLarge : 0.156);
            }

            // Redshift
            progressMessage = "Fetching Redshift pricing...";
            progressPercent = 75;
            ObjectNode redshiftNode = newRoot.putObject("redshift");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = redshiftNode.putObject(regionEntry.getKey());
                for (String type : REDSHIFT_TYPES) {
                    try {
                        double hourly = fetchRedshiftHourly(client, type, regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
            }

            // OpenSearch
            progressMessage = "Fetching OpenSearch pricing...";
            progressPercent = 80;
            ObjectNode osNode = newRoot.putObject("opensearch");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = osNode.putObject(regionEntry.getKey());
                double baseLarge = -1;
                for (String type : OPENSEARCH_TYPES) {
                    try {
                        double hourly = fetchGenericHourly(client, "AmazonES", type.replace(".search", ""), regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            if (type.equals("m5.large.search")) baseLarge = hourly;
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                regionNode.put("_baseLargeHourly", baseLarge > 0 ? baseLarge : 0.135);
            }

            // SageMaker
            progressMessage = "Fetching SageMaker pricing...";
            progressPercent = 85;
            ObjectNode smNode = newRoot.putObject("sagemaker");
            for (var regionEntry : REGION_LOCATION.entrySet()) {
                ObjectNode regionNode = smNode.putObject(regionEntry.getKey());
                double baseLarge = -1;
                for (String type : SAGEMAKER_TYPES) {
                    try {
                        double hourly = fetchGenericHourly(client, "AmazonSageMaker", type, regionEntry.getValue());
                        if (hourly > 0) {
                            regionNode.put(type, hourly);
                            if (type.equals("ml.t3.large")) baseLarge = hourly;
                            updated++;
                        }
                    } catch (Exception e) {
                        failed++;
                    }
                }
                regionNode.put("_baseLargeHourly", baseLarge > 0 ? baseLarge : 0.0992);
            }

            // Services (NAT, S3, EBS, etc.) - copy existing since these rarely change
            // and the Pricing API queries for these are more complex
            ObjectNode servicesNode = newRoot.putObject("services");
            copyExistingServices(servicesNode);

            // Write to file
            progressMessage = "Saving pricing data...";
            progressPercent = 90;
            Path pricingFile = getPricingFilePath();
            mapper.writerWithDefaultPrettyPrinter().writeValue(pricingFile.toFile(), newRoot);
            log.info("Pricing file written to: {}", pricingFile);

            // Reload in memory and clear pricing cache
            pricingData.loadFromNode(newRoot);
            var pricingCache = cacheManager.getCache("pricing");
            if (pricingCache != null) pricingCache.clear();
            progressMessage = "Complete";
            progressPercent = 100;
            log.info("Pricing refresh complete: {} rates updated, {} failed", updated, failed);

            return Map.of(
                    "status", "success",
                    "message", String.format("Pricing updated: %d rates refreshed, %d failed", updated, failed),
                    "updated", updated,
                    "failed", failed,
                    "lastVerified", LocalDate.now().toString()
            );

        } catch (Exception e) {
            log.error("Pricing refresh failed: {}", e.getMessage());
            return Map.of("status", "error", "message", "Pricing refresh failed: " + e.getMessage());
        } finally {
            if (client != null) client.close();
            refreshing.set(false);
        }
    }

    /**
     * Copies the existing {@code "services"} section from the previous pricing file into the new root node.
     * Tries the disk file first (may have been updated by a previous refresh), then falls back to
     * the classpath-bundled file. Service-level rates (NAT Gateway, S3, EBS per-GB, Lambda, etc.)
     * are not auto-refreshed because their Pricing API queries are more complex.
     *
     * @param servicesNode the empty ObjectNode to populate with existing service rate data
     */
    private void copyExistingServices(ObjectNode servicesNode) {
        // Try disk file first (may have been updated by a previous refresh), then classpath
        java.nio.file.Path diskFile = getPricingFilePath();
        if (java.nio.file.Files.exists(diskFile)) {
            try (java.io.InputStream is = java.nio.file.Files.newInputStream(diskFile)) {
                JsonNode existing = mapper.readTree(is);
                JsonNode existingServices = existing.path("services");
                if (!existingServices.isMissingNode()) {
                    existingServices.fieldNames().forEachRemaining(region -> {
                        servicesNode.set(region, existingServices.get(region).deepCopy());
                    });
                    return;
                }
            } catch (IOException e) {
                log.debug("Could not read disk services pricing, trying classpath: {}", e.getMessage());
            }
        }

        // Fallback to classpath bundled file
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("pricing/regional-pricing.json")) {
            if (is != null) {
                JsonNode existing = mapper.readTree(is);
                JsonNode existingServices = existing.path("services");
                if (!existingServices.isMissingNode()) {
                    existingServices.fieldNames().forEachRemaining(region -> {
                        servicesNode.set(region, existingServices.get(region).deepCopy());
                    });
                }
            }
        } catch (IOException e) {
            log.warn("Could not copy existing services pricing: {}", e.getMessage());
        }
    }

    /**
     * Returns the path to the pricing file on disk ({@code ~/.cloud-sentinel/regional-pricing.json}).
     * Creates the parent directory if it does not exist.
     *
     * @return the absolute path to the pricing file
     */
    private Path getPricingFilePath() {
        Path appDir = Path.of(System.getProperty("user.home"), ".cloud-sentinel");
        try {
            Files.createDirectories(appDir);
        } catch (IOException e) {
            log.warn("Failed to create app directory: {}", e.getMessage());
        }
        return appDir.resolve("regional-pricing.json");
    }

    /**
     * Builds an AWS credentials provider for the given profile name.
     * Uses {@link ProfileCredentialsProvider} for named profiles, or
     * {@link DefaultCredentialsProvider} for null/blank profile names.
     *
     * @param profileName the AWS profile name, or null/blank for default credentials
     * @return the appropriate credentials provider
     */
    private AwsCredentialsProvider buildCredentials(String profileName) {
        if (profileName != null && !profileName.isBlank()) {
            return ProfileCredentialsProvider.builder()
                    .profileName(profileName)
                    .build();
        }
        return DefaultCredentialsProvider.create();
    }

    /**
     * Fetches the hourly on-demand price for an EC2 instance type in the given location.
     * Filters for Linux, Shared tenancy, no pre-installed software, Used capacity status.
     *
     * @param client       the Pricing API client
     * @param instanceType the EC2 instance type (e.g. {@code "m5.large"})
     * @param location     the human-readable location name (e.g. {@code "US East (N. Virginia)"})
     * @return the hourly price in USD, or 0.0 if not found
     */
    private double fetchEc2Hourly(PricingClient client, String instanceType, String location) {
        GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(
                        filter("instanceType", instanceType),
                        filter("location", location),
                        filter("operatingSystem", "Linux"),
                        filter("tenancy", "Shared"),
                        filter("preInstalledSw", "NA"),
                        filter("capacitystatus", "Used")
                )
                .maxResults(1)
                .build());
        return extractHourlyPrice(response);
    }

    /**
     * Fetches the hourly on-demand price for an RDS instance type in the given location.
     * Filters for MySQL engine, Single-AZ deployment.
     *
     * @param client       the Pricing API client
     * @param instanceType the RDS instance type (e.g. {@code "db.m5.large"})
     * @param location     the human-readable location name
     * @return the hourly price in USD, or 0.0 if not found
     */
    private double fetchRdsHourly(PricingClient client, String instanceType, String location) {
        GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                .serviceCode("AmazonRDS")
                .filters(
                        filter("instanceType", instanceType),
                        filter("location", location),
                        filter("databaseEngine", "MySQL"),
                        filter("deploymentOption", "Single-AZ")
                )
                .maxResults(1)
                .build());
        return extractHourlyPrice(response);
    }

    /**
     * Fetches the hourly on-demand price for an ElastiCache node type in the given location.
     * Filters for Redis engine.
     *
     * @param client   the Pricing API client
     * @param nodeType the cache node type (e.g. {@code "cache.m5.large"})
     * @param location the human-readable location name
     * @return the hourly price in USD, or 0.0 if not found
     */
    private double fetchElastiCacheHourly(PricingClient client, String nodeType, String location) {
        GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                .serviceCode("AmazonElastiCache")
                .filters(
                        filter("instanceType", nodeType),
                        filter("location", location),
                        filter("cacheEngine", "Redis")
                )
                .maxResults(1)
                .build());
        return extractHourlyPrice(response);
    }

    /**
     * Fetches the hourly on-demand price for a Redshift node type in the given location.
     *
     * @param client   the Pricing API client
     * @param nodeType the Redshift node type (e.g. {@code "dc2.large"})
     * @param location the human-readable location name
     * @return the hourly price in USD, or 0.0 if not found
     */
    private double fetchRedshiftHourly(PricingClient client, String nodeType, String location) {
        GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                .serviceCode("AmazonRedshift")
                .filters(
                        filter("instanceType", nodeType),
                        filter("location", location)
                )
                .maxResults(1)
                .build());
        return extractHourlyPrice(response);
    }

    /**
     * Fetches the hourly on-demand price for a generic AWS service by service code and instance type.
     * Used for OpenSearch (AmazonES) and SageMaker (AmazonSageMaker).
     *
     * @param client       the Pricing API client
     * @param serviceCode  the AWS service code (e.g. {@code "AmazonES"}, {@code "AmazonSageMaker"})
     * @param instanceType the instance type to look up
     * @param location     the human-readable location name
     * @return the hourly price in USD, or 0.0 if not found
     */
    private double fetchGenericHourly(PricingClient client, String serviceCode, String instanceType, String location) {
        GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                .serviceCode(serviceCode)
                .filters(
                        filter("instanceType", instanceType),
                        filter("location", location)
                )
                .maxResults(1)
                .build());
        return extractHourlyPrice(response);
    }

    /**
     * Creates a TERM_MATCH filter for AWS Pricing API queries.
     *
     * @param field the filter field name
     * @param value the value to match
     * @return the constructed filter
     */
    private Filter filter(String field, String value) {
        return Filter.builder().type(FilterType.TERM_MATCH).field(field).value(value).build();
    }

    /**
     * Extracts the first positive USD price from an AWS Pricing API response.
     * Parses the JSON price list and navigates to {@code terms.OnDemand.priceDimensions}.
     *
     * @param response the raw response from the Pricing API
     * @return the hourly price in USD, or 0.0 if no valid price is found
     */
    private double extractHourlyPrice(GetProductsResponse response) {
        try {
            if (response.priceList().isEmpty()) return 0.0;
            JsonNode root = mapper.readTree(response.priceList().getFirst());
            JsonNode terms = root.path("terms").path("OnDemand");
            for (JsonNode offer : terms) {
                for (JsonNode dimension : offer.path("priceDimensions")) {
                    String usd = dimension.path("pricePerUnit").path("USD").asText("0");
                    double price = Double.parseDouble(usd);
                    if (price > 0) return price;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse pricing response: {}", e.getMessage());
        }
        return 0.0;
    }

}
