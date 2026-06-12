package com.cloudsentinel.service.pricing;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.util.Map;

/**
 * Main pricing facade for estimating monthly costs of AWS resources across all supported services.
 *
 * <p>This service implements a 3-tier pricing resolution chain for each resource type:</p>
 * <ol>
 *   <li><strong>AWS Pricing API</strong> -- real-time on-demand pricing fetched from the
 *       {@code us-east-1} Pricing endpoint. Results are cached via Spring's {@code @Cacheable("pricing")}
 *       with a 24-hour Caffeine TTL, so repeated lookups for the same resource/region are free.</li>
 *   <li><strong>{@link FallbackPricing}</strong> -- used automatically when the Pricing API call
 *       fails (network error, missing credentials, throttling). Derives costs from the regional
 *       pricing JSON file using per-instance rates and size multipliers.</li>
 *   <li><strong>{@link RegionalPricingData}</strong> -- the lowest tier, supplying raw per-region
 *       hourly/monthly rates loaded from {@code regional-pricing.json} on disk or classpath.</li>
 * </ol>
 *
 * <p>Some resource types (snapshots, Lambda, SQS, SNS, Route 53, EFS, KMS, CloudWatch, etc.)
 * bypass the Pricing API entirely and delegate straight to {@link FallbackPricing} because their
 * pricing models are simple per-unit rates that rarely change.</p>
 *
 * <p>All AWS SDK clients are built via {@link com.cloudsentinel.config.ReadOnlyAwsClientFactory}
 * to enforce the application's read-only security guardrail.</p>
 *
 * @see FallbackPricing
 * @see RegionalPricingData
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);
    /** Standard assumption for converting hourly rates to monthly costs (365 days / 12 months * 24 hours). */
    private static final double HOURS_PER_MONTH = 730.0;
    private static final ObjectMapper mapper = new ObjectMapper();

    private final FallbackPricing fallbackPricing;

    /**
     * Constructs the pricing service with the given fallback pricing provider.
     *
     * @param fallbackPricing the fallback pricing component used when the AWS Pricing API is unavailable
     */
    public PricingService(FallbackPricing fallbackPricing) {
        this.fallbackPricing = fallbackPricing;
    }

    /**
     * Maps AWS region codes (e.g. {@code us-east-1}) to the human-readable location names
     * required by the AWS Pricing API's {@code location} filter (e.g. {@code "US East (N. Virginia)"}).
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

    /**
     * Estimates the monthly cost of an EC2 instance. Tries the AWS Pricing API first (cached),
     * filtering for Linux/Shared/On-Demand pricing, then falls back to {@link FallbackPricing}.
     *
     * @param instanceType the EC2 instance type (e.g. {@code "m5.large"}, {@code "t3.micro"})
     * @param region       the AWS region code (e.g. {@code "us-east-1"})
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getEc2Price(String instanceType, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
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
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for EC2 {}, using fallback: {}", instanceType, e.getMessage());
        }
        return fallbackPricing.getEc2Price(instanceType, region);
    }

    /**
     * Estimates the monthly cost of an RDS instance. Tries the AWS Pricing API first (cached),
     * filtering for Single-AZ deployment with the given engine, then falls back to {@link FallbackPricing}.
     *
     * @param instanceClass the RDS instance class (e.g. {@code "db.m5.large"})
     * @param engine        the database engine name as expected by the Pricing API (e.g. {@code "MySQL"}, {@code "PostgreSQL"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getRdsPrice(String instanceClass, String engine, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRDS")
                        .filters(
                                filter("instanceType", instanceClass),
                                filter("location", location),
                                filter("databaseEngine", engine),
                                filter("deploymentOption", "Single-AZ")
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for RDS {}, using fallback: {}", instanceClass, e.getMessage());
        }
        return fallbackPricing.getRdsPrice(instanceClass, region);
    }

    /**
     * Estimates the monthly cost of storing an EBS snapshot. Delegates directly to {@link FallbackPricing}
     * (no Pricing API call) since snapshot storage is a simple per-GB rate.
     *
     * @param sizeGb the snapshot size in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getEbsSnapshotPrice(int sizeGb, String region) {
        return fallbackPricing.getEbsSnapshotPrice(sizeGb, region);
    }

    /**
     * Estimates the monthly cost of storing an RDS snapshot. Delegates directly to {@link FallbackPricing}
     * (no Pricing API call) since snapshot storage is a simple per-GB rate.
     *
     * @param sizeGb the snapshot size in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getRdsSnapshotPrice(int sizeGb, String region) {
        return fallbackPricing.getRdsSnapshotPrice(sizeGb, region);
    }

    /**
     * Estimates the monthly cost of an EBS volume. Tries the AWS Pricing API first (cached),
     * filtering by volume API name and the Storage product family, then falls back to {@link FallbackPricing}.
     * Note: the Pricing API returns a per-GB rate (not hourly) for storage products.
     *
     * @param sizeGb     the volume size in gigabytes
     * @param volumeType the EBS volume type API name (e.g. {@code "gp3"}, {@code "io1"}, {@code "st1"})
     * @param region     the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getEbsPrice(int sizeGb, String volumeType, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonEC2")
                        .filters(
                                filter("volumeApiName", volumeType),
                                filter("location", location),
                                filter("productFamily", "Storage")
                        )
                        .build());
                double pricePerGb = extractHourlyPrice(response);
                if (pricePerGb > 0) {
                    return Math.round(sizeGb * pricePerGb * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for EBS {}, using fallback: {}", volumeType, e.getMessage());
        }
        return fallbackPricing.getEbsPrice(sizeGb, volumeType, region);
    }

    /**
     * Estimates the monthly cost of an Elastic Load Balancer. Tries the AWS Pricing API first (cached),
     * mapping the load balancer type to the appropriate product family, then falls back to {@link FallbackPricing}.
     *
     * @param type   the load balancer type: {@code "application"}, {@code "network"}, or {@code "classic"}
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getElbPrice(String type, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            String productFamily = switch (type.toLowerCase()) {
                case "application" -> "Load Balancer-Application";
                case "network" -> "Load Balancer-Network";
                default -> "Load Balancer";
            };
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AWSELB")
                        .filters(
                                filter("location", location),
                                filter("productFamily", productFamily)
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for ELB {}, using fallback: {}", type, e.getMessage());
        }
        return fallbackPricing.getElbPrice(type, region);
    }

    /**
     * Estimates the monthly cost of an ElastiCache node. Tries the AWS Pricing API first (cached),
     * filtering for Redis engine pricing, then falls back to {@link FallbackPricing}.
     *
     * @param cacheNodeType the cache node type (e.g. {@code "cache.m5.large"}, {@code "cache.t3.micro"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getElastiCachePrice(String cacheNodeType, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonElastiCache")
                        .filters(
                                filter("instanceType", cacheNodeType),
                                filter("location", location),
                                filter("cacheEngine", "Redis")
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for ElastiCache {}, using fallback: {}", cacheNodeType, e.getMessage());
        }
        return fallbackPricing.getElastiCachePrice(cacheNodeType, region);
    }

    /**
     * Estimates the monthly cost of a NAT Gateway. Tries the AWS Pricing API first (cached),
     * filtering by the NAT Gateway product family, then falls back to {@link FallbackPricing}.
     * Does not include data processing charges.
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getNatGatewayPrice(String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonEC2")
                        .filters(
                                filter("location", location),
                                filter("productFamily", "NAT Gateway")
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for NAT Gateway, using fallback: {}", e.getMessage());
        }
        return fallbackPricing.getNatGatewayPrice(region);
    }

    /**
     * Returns the per-GB monthly cost for S3 Standard storage. Tries the AWS Pricing API first (cached),
     * filtering for General Purpose / Standard volume type, then falls back to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the S3 Standard storage price per GB per month in USD
     */
    @Cacheable("pricing")
    public double getS3PricePerGb(String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonS3")
                        .filters(
                                filter("location", location),
                                filter("storageClass", "General Purpose"),
                                filter("volumeType", "Standard")
                        )
                        .build());
                double pricePerGb = extractHourlyPrice(response);
                if (pricePerGb > 0) {
                    return pricePerGb;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for S3, using fallback: {}", e.getMessage());
        }
        return fallbackPricing.getS3PricePerGb(region);
    }

    /**
     * Estimates the monthly cost of a DynamoDB table in provisioned capacity mode. Tries the AWS Pricing API
     * first (cached), fetching separate read and write unit prices, then falls back to {@link FallbackPricing}.
     *
     * @param rcu    the provisioned read capacity units
     * @param wcu    the provisioned write capacity units
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getDynamoDbPrice(long rcu, long wcu, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse readResponse = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonDynamoDB")
                        .filters(
                                filter("location", location),
                                filter("group", "DDB-ReadUnits")
                        )
                        .build());
                GetProductsResponse writeResponse = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonDynamoDB")
                        .filters(
                                filter("location", location),
                                filter("group", "DDB-WriteUnits")
                        )
                        .build());
                double readPrice = extractHourlyPrice(readResponse);
                double writePrice = extractHourlyPrice(writeResponse);
                if (readPrice > 0 && writePrice > 0) {
                    return Math.round((rcu * readPrice + wcu * writePrice) * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for DynamoDB, using fallback: {}", e.getMessage());
        }
        return fallbackPricing.getDynamoDbProvisionedPrice(rcu, wcu, region);
    }

    /**
     * Returns the monthly cost of a Route 53 hosted zone. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly hosted zone cost in USD
     */
    public double getRoute53HostedZonePrice(String region) {
        return fallbackPricing.getRoute53HostedZonePrice(region);
    }

    /**
     * Estimates the monthly cost of EFS storage. Delegates directly to {@link FallbackPricing}.
     *
     * @param sizeGb the total EFS storage in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getEfsPrice(double sizeGb, String region) {
        return fallbackPricing.getEfsPrice(sizeGb, region);
    }

    /**
     * Estimates the monthly cost of an OpenSearch domain. Delegates directly to {@link FallbackPricing}
     * (no Pricing API call) using instance-rate lookup with size multiplier fallback.
     *
     * @param instanceType  the OpenSearch instance type (e.g. {@code "m5.large.search"})
     * @param instanceCount the number of data nodes in the domain
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getOpenSearchPrice(String instanceType, int instanceCount, String region) {
        return fallbackPricing.getOpenSearchPrice(instanceType, instanceCount, region);
    }

    /**
     * Estimates the monthly cost of an AWS Transfer Family server endpoint. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getTransferFamilyPrice(String region) {
        return fallbackPricing.getTransferFamilyPrice(region);
    }

    /**
     * Estimates the monthly cost of a SageMaker notebook instance. Delegates directly to {@link FallbackPricing}
     * using instance-rate lookup with size multiplier fallback.
     *
     * @param instanceType the SageMaker instance type (e.g. {@code "ml.t3.medium"}, {@code "ml.m5.xlarge"})
     * @param region       the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getSageMakerNotebookPrice(String instanceType, String region) {
        return fallbackPricing.getSageMakerNotebookPrice(instanceType, region);
    }

    /**
     * Estimates the monthly cost of Kinesis Data Streams shards. Delegates directly to {@link FallbackPricing}.
     *
     * @param shardCount the number of open shards
     * @param region     the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getKinesisShardPrice(int shardCount, String region) {
        return fallbackPricing.getKinesisShardPrice(shardCount, region);
    }

    /**
     * Estimates the monthly cost of an EKS cluster control plane. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getEksClusterPrice(String region) {
        return fallbackPricing.getEksClusterPrice(region);
    }

    /**
     * Estimates the monthly cost of a Redshift cluster. Tries the AWS Pricing API first (cached),
     * multiplying the per-node hourly rate by node count and hours per month, then falls back to {@link FallbackPricing}.
     *
     * @param nodeType  the Redshift node type (e.g. {@code "dc2.large"}, {@code "ra3.xlplus"})
     * @param nodeCount the number of nodes in the cluster
     * @param region    the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getRedshiftPrice(String nodeType, int nodeCount, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRedshift")
                        .filters(
                                filter("instanceType", nodeType),
                                filter("location", location)
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * nodeCount * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for Redshift {}, using fallback: {}", nodeType, e.getMessage());
        }
        return fallbackPricing.getRedshiftPrice(nodeType, nodeCount, region);
    }

    /**
     * Estimates the monthly cost of an Aurora DB instance. Tries the AWS Pricing API first (cached),
     * using the RDS service code with the Aurora-specific engine, then falls back to {@link FallbackPricing}
     * which applies a 1.2x multiplier over standard RDS pricing.
     *
     * @param instanceClass the Aurora instance class (e.g. {@code "db.r5.large"})
     * @param engine        the Aurora engine variant (e.g. {@code "Aurora MySQL"}, {@code "Aurora PostgreSQL"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    @Cacheable("pricing")
    public double getAuroraPrice(String instanceClass, String engine, String region) {
        try {
            String location = REGION_LOCATION.getOrDefault(region, "US East (N. Virginia)");
            try (PricingClient client = buildClient()) {
                GetProductsResponse response = client.getProducts(GetProductsRequest.builder()
                        .serviceCode("AmazonRDS")
                        .filters(
                                filter("instanceType", instanceClass),
                                filter("location", location),
                                filter("databaseEngine", engine),
                                filter("deploymentOption", "Single-AZ")
                        )
                        .build());
                double hourly = extractHourlyPrice(response);
                if (hourly > 0) {
                    return Math.round(hourly * HOURS_PER_MONTH * 100.0) / 100.0;
                }
            }
        } catch (Exception e) {
            log.warn("Pricing API failed for Aurora {}, using fallback: {}", instanceClass, e.getMessage());
        }
        return fallbackPricing.getAuroraPrice(instanceClass, region);
    }

    /**
     * Estimates the monthly cost of a Lambda function based on invocation count, duration, and memory.
     * Delegates directly to {@link FallbackPricing} (no Pricing API call).
     *
     * @param invocations   the number of invocations per month
     * @param avgDurationMs the average invocation duration in milliseconds
     * @param memoryMb      the configured memory in megabytes
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getLambdaPrice(long invocations, long avgDurationMs, int memoryMb, String region) {
        return fallbackPricing.getLambdaPrice(invocations, avgDurationMs, memoryMb, region);
    }

    /**
     * Estimates the monthly cost of an SQS queue. Delegates directly to {@link FallbackPricing}.
     *
     * @param messagesPerMonth the number of messages processed per month
     * @param region           the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getSqsPrice(long messagesPerMonth, String region) {
        return fallbackPricing.getSqsPrice(messagesPerMonth, region);
    }

    /**
     * Estimates the monthly cost of an SNS topic. Delegates directly to {@link FallbackPricing}.
     *
     * @param publishesPerMonth the number of publish operations per month
     * @param region            the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getSnsPrice(long publishesPerMonth, String region) {
        return fallbackPricing.getSnsPrice(publishesPerMonth, region);
    }

    /**
     * Returns the monthly cost of a KMS customer-managed key. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly key cost in USD
     */
    public double getKmsKeyPrice(String region) {
        return fallbackPricing.getKmsKeyPrice(region);
    }

    /**
     * Estimates the monthly cost of CloudWatch alarms. Delegates directly to {@link FallbackPricing}.
     *
     * @param count  the number of standard alarms
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getCloudWatchAlarmPrice(int count, String region) {
        return fallbackPricing.getCloudWatchAlarmPrice(count, region);
    }

    /**
     * Estimates the monthly cost of CloudWatch Logs storage. Delegates directly to {@link FallbackPricing}.
     *
     * @param storedGb the total stored log data in gigabytes
     * @param region   the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getCloudWatchLogStoragePrice(double storedGb, String region) {
        return fallbackPricing.getCloudWatchLogStoragePrice(storedGb, region);
    }

    /**
     * Returns the monthly cost of a Secrets Manager secret. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly secret cost in USD
     */
    public double getSecretPrice(String region) {
        return fallbackPricing.getSecretPrice(region);
    }

    /**
     * Returns the monthly cost of an SSM advanced parameter. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly advanced parameter cost in USD
     */
    public double getAdvancedParameterPrice(String region) {
        return fallbackPricing.getAdvancedParameterPrice(region);
    }

    /**
     * Returns the monthly cost of an unattached Elastic IP address. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly Elastic IP cost in USD
     */
    public double getElasticIpPrice(String region) {
        return fallbackPricing.getElasticIpPrice(region);
    }

    /**
     * Estimates the monthly cost of a DynamoDB table in on-demand capacity mode.
     * Delegates directly to {@link FallbackPricing}.
     *
     * @param consumedReads  the daily consumed read request units
     * @param consumedWrites the daily consumed write request units
     * @param region         the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getDynamoDbOnDemandPrice(double consumedReads, double consumedWrites, String region) {
        return fallbackPricing.getDynamoDbOnDemandPrice(consumedReads, consumedWrites, region);
    }

    /**
     * Estimates the monthly cost of a WAF Web ACL including its rules. Delegates directly to {@link FallbackPricing}.
     *
     * @param ruleCount the number of rules attached to the Web ACL
     * @param region    the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getWafPrice(int ruleCount, String region) {
        return fallbackPricing.getWafPrice(ruleCount, region);
    }

    /**
     * Returns the monthly cost of a Managed Grafana workspace. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the monthly workspace cost in USD
     */
    public double getGrafanaWorkspacePrice(String region) {
        return fallbackPricing.getGrafanaWorkspacePrice(region);
    }

    /**
     * Returns the per-GB monthly cost for ECR image storage. Delegates directly to {@link FallbackPricing}.
     *
     * @param region the AWS region code
     * @return the ECR storage price per GB per month in USD
     */
    public double getEcrPricePerGb(String region) {
        return fallbackPricing.getEcrPricePerGb(region);
    }

    /**
     * Estimates the monthly cost of an API Gateway based on daily request volume.
     * Delegates directly to {@link FallbackPricing}.
     *
     * @param requestsPerDay the average number of API requests per day
     * @param region         the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getApiGatewayPrice(double requestsPerDay, String region) {
        return fallbackPricing.getApiGatewayPrice(requestsPerDay, region);
    }

    /**
     * Estimates the monthly cost of Step Functions state machine executions.
     * Delegates directly to {@link FallbackPricing}. Assumes approximately 10 state transitions per execution.
     *
     * @param executionsPerDay the average number of executions per day
     * @param region           the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getStepFunctionsPrice(double executionsPerDay, String region) {
        return fallbackPricing.getStepFunctionsPrice(executionsPerDay, region);
    }

    /**
     * Estimates the monthly cost of AWS Glue jobs based on DPU-hours consumed.
     * Delegates directly to {@link FallbackPricing}.
     *
     * @param dpuHours the total DPU-hours consumed per month
     * @param region   the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getGlueDpuPrice(double dpuHours, String region) {
        return fallbackPricing.getGlueDpuPrice(dpuHours, region);
    }

    /**
     * Builds a read-only AWS Pricing API client targeting {@code us-east-1} (the only region
     * that serves the Pricing API).
     *
     * @return a new {@link PricingClient} instance with the read-only interceptor attached
     */
    private PricingClient buildClient() {
        return ReadOnlyAwsClientFactory.build(PricingClient.builder(), Region.US_EAST_1);
    }

    /**
     * Creates a {@link Filter} for use with AWS Pricing API product queries.
     *
     * @param field the filter field name (e.g. {@code "instanceType"}, {@code "location"})
     * @param value the value to match
     * @return a TERM_MATCH filter for the given field and value
     */
    private Filter filter(String field, String value) {
        return Filter.builder()
                .type(FilterType.TERM_MATCH)
                .field(field)
                .value(value)
                .build();
    }

    /**
     * Extracts the first positive USD price from an AWS Pricing API response. Parses the JSON price list,
     * navigates to {@code terms.OnDemand}, and iterates through price dimensions. Only accepts prices
     * whose unit contains "hr", "hour", "unit", or "quantity" (skipping GB-month, per-request, etc.).
     *
     * @param response the raw response from {@link PricingClient#getProducts}
     * @return the hourly (or per-unit) price in USD, or {@code 0.0} if no valid price is found
     */
    private double extractHourlyPrice(GetProductsResponse response) {
        try {
            if (response.priceList().isEmpty()) {
                return 0.0;
            }
            JsonNode root = mapper.readTree(response.priceList().getFirst());
            JsonNode terms = root.path("terms").path("OnDemand");
            for (JsonNode offer : terms) {
                for (JsonNode dimension : offer.path("priceDimensions")) {
                    String unit = dimension.path("unit").asText("").toLowerCase();
                    // Only accept hourly or per-unit rates — skip GB-month, per-request, etc.
                    if (!unit.isEmpty() && !unit.contains("hr") && !unit.contains("hour")
                            && !unit.contains("unit") && !unit.contains("quantity")) {
                        continue;
                    }
                    String usd = dimension.path("pricePerUnit").path("USD").asText("0");
                    double price = Double.parseDouble(usd);
                    if (price > 0) {
                        return price;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse pricing response: {}", e.getMessage());
        }
        return 0.0;
    }
}
