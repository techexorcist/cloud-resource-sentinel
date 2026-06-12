package com.cloudsentinel.service.pricing;

import org.springframework.stereotype.Component;

/**
 * Regional fallback pricing loaded from {@code pricing/regional-pricing.json}.
 *
 * <p>This component is the second tier of the pricing resolution chain, used when the live
 * AWS Pricing API is unavailable (network errors, missing credentials, throttling). It reads
 * per-region rates from {@link RegionalPricingData} and applies hardcoded default values when
 * the JSON file lacks data for a particular service/region combination.</p>
 *
 * <p>For instance-based services (EC2, RDS, ElastiCache, OpenSearch, SageMaker), the
 * {@link #instancePrice(String, String, String, double)} helper first attempts an exact match
 * for the instance type in the regional pricing data. If no exact match is found, it falls back
 * to the {@code _baseLargeHourly} rate for that service/region and applies a
 * {@link #sizeMultiplier(String)} derived from the instance type's size suffix
 * (nano/micro/small/medium/large/xlarge/etc.).</p>
 *
 * <p>Update the JSON file periodically with fresh rates, either manually or via
 * {@link PricingRefreshService}.</p>
 *
 * @see PricingService
 * @see RegionalPricingData
 */
@Component
public class FallbackPricing {

    /** Standard assumption for converting hourly rates to monthly costs (365 / 12 * 24). */
    private static final double HOURS_PER_MONTH = 730.0;

    private final RegionalPricingData pricingData;

    /**
     * Constructs the fallback pricing component with the given regional pricing data source.
     *
     * @param pricingData the regional pricing data loaded from the JSON file
     */
    public FallbackPricing(RegionalPricingData pricingData) {
        this.pricingData = pricingData;
    }

    // ── Compute ──

    /**
     * Estimates the monthly cost of an EC2 instance using regional pricing data.
     * Uses {@link #instancePrice} with a default base-large hourly rate of $0.096 (m5.large equivalent).
     *
     * @param instanceType the EC2 instance type (e.g. {@code "m5.large"}, {@code "t3.micro"})
     * @param region       the AWS region code (e.g. {@code "us-east-1"})
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getEc2Price(String instanceType, String region) {
        return instancePrice("ec2", instanceType, region, 0.096);
    }

    /**
     * Estimates the monthly cost of an RDS instance using regional pricing data.
     * Uses {@link #instancePrice} with a default base-large hourly rate of $0.171 (db.m5.large equivalent).
     *
     * @param instanceClass the RDS instance class (e.g. {@code "db.m5.large"}, {@code "db.t3.micro"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getRdsPrice(String instanceClass, String region) {
        return instancePrice("rds", instanceClass, region, 0.171);
    }

    /**
     * Estimates the monthly cost of an Aurora DB instance. Applies a 1.2x multiplier over
     * the standard RDS price for the same instance class and region.
     *
     * @param instanceClass the Aurora instance class (e.g. {@code "db.r5.large"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getAuroraPrice(String instanceClass, String region) {
        return round(getRdsPrice(instanceClass, region) * 1.2);
    }

    /**
     * Estimates the monthly cost of an ElastiCache node using regional pricing data.
     * Uses {@link #instancePrice} with a default base-large hourly rate of $0.156 (cache.m5.large equivalent).
     *
     * @param cacheNodeType the cache node type (e.g. {@code "cache.m5.large"}, {@code "cache.t3.micro"})
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getElastiCachePrice(String cacheNodeType, String region) {
        return instancePrice("elasticache", cacheNodeType, region, 0.156);
    }

    /**
     * Estimates the monthly cost of a Redshift cluster. Looks up the per-node hourly rate
     * from the pricing data, defaulting to $0.25/hr if not found, then multiplies by node count.
     *
     * @param nodeType  the Redshift node type (e.g. {@code "dc2.large"}, {@code "ra3.xlplus"})
     * @param nodeCount the number of nodes in the cluster
     * @param region    the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getRedshiftPrice(String nodeType, int nodeCount, String region) {
        double hourly = pricingData.getInstanceRate("redshift", region, nodeType);
        if (hourly <= 0) hourly = 0.25;
        return round(hourly * HOURS_PER_MONTH * nodeCount);
    }

    /**
     * Estimates the monthly cost of an EKS cluster control plane.
     * Defaults to $0.10/hr if no regional rate is found.
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getEksClusterPrice(String region) {
        return round(getServiceRateOrDefault(region, "eksClusterHourly", 0.10) * HOURS_PER_MONTH);
    }

    /**
     * Estimates the monthly cost of an OpenSearch domain. Tries exact match with the
     * {@code .search} suffix first, then normalized name, then falls back to the base-large
     * hourly rate with size multiplier. Defaults to $0.135/hr for base-large if not found.
     *
     * @param instanceType  the OpenSearch instance type (e.g. {@code "m5.large.search"})
     * @param instanceCount the number of data nodes in the domain
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getOpenSearchPrice(String instanceType, int instanceCount, String region) {
        String normalizedType = instanceType != null ? instanceType.replace(".search", "") : instanceType;
        // Try exact match with .search suffix first, then normalized
        double hourly = pricingData.getInstanceRate("opensearch", region, instanceType);
        if (hourly <= 0) hourly = pricingData.getInstanceRate("opensearch", region, normalizedType);
        if (hourly <= 0) {
            double baseLarge = pricingData.getBaseLargeHourly("opensearch", region);
            if (baseLarge <= 0) baseLarge = 0.135;
            hourly = sizeMultiplier(normalizedType) * baseLarge;
        }
        return round(hourly * HOURS_PER_MONTH * instanceCount);
    }

    /**
     * Estimates the monthly cost of a SageMaker notebook instance. Tries an exact match
     * in regional pricing data, then falls back to base-large hourly rate with size multiplier.
     * Defaults to $0.0992/hr for base-large if not found.
     *
     * @param instanceType the SageMaker instance type (e.g. {@code "ml.t3.medium"}, {@code "ml.m5.xlarge"})
     * @param region       the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getSageMakerNotebookPrice(String instanceType, String region) {
        double hourly = pricingData.getInstanceRate("sagemaker", region, instanceType);
        if (hourly <= 0) {
            double baseLarge = pricingData.getBaseLargeHourly("sagemaker", region);
            if (baseLarge <= 0) baseLarge = 0.0992;
            hourly = sizeMultiplier(instanceType) * baseLarge;
        }
        return round(hourly * HOURS_PER_MONTH);
    }

    // ── Storage ──

    /**
     * Estimates the monthly cost of an EBS volume based on size and volume type.
     * Tries a dynamic key (e.g. {@code "ebsGp3PerGb"}) from the services section,
     * then falls back to hardcoded per-GB defaults for each volume type.
     *
     * @param sizeGb     the volume size in gigabytes
     * @param volumeType the EBS volume type (e.g. {@code "gp2"}, {@code "gp3"}, {@code "io1"}, {@code "st1"}, {@code "sc1"})
     * @param region     the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getEbsPrice(int sizeGb, String volumeType, String region) {
        String key = "ebs" + capitalize(volumeType) + "PerGb";
        double perGb = pricingData.getServiceRate(region, key);
        if (perGb <= 0) {
            perGb = switch (volumeType.toLowerCase()) {
                case "gp2" -> getServiceRateOrDefault(region, "ebsGp2PerGb", 0.10);
                case "gp3" -> getServiceRateOrDefault(region, "ebsGp3PerGb", 0.08);
                case "io1", "io2" -> getServiceRateOrDefault(region, "ebsIo1PerGb", 0.125);
                case "st1" -> getServiceRateOrDefault(region, "ebsSt1PerGb", 0.045);
                case "sc1" -> getServiceRateOrDefault(region, "ebsSc1PerGb", 0.015);
                default -> 0.10;
            };
        }
        return round(sizeGb * perGb);
    }

    /**
     * Estimates the monthly cost of storing an EBS snapshot.
     *
     * @param sizeGb the snapshot size in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getEbsSnapshotPrice(int sizeGb, String region) {
        return round(sizeGb * getServiceRateOrDefault(region, "ebsSnapshotPerGb", 0.05));
    }

    /**
     * Estimates the monthly cost of storing an RDS snapshot.
     *
     * @param sizeGb the snapshot size in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getRdsSnapshotPrice(int sizeGb, String region) {
        return round(sizeGb * getServiceRateOrDefault(region, "rdsSnapshotPerGb", 0.095));
    }

    /**
     * Returns the per-GB monthly cost for S3 Standard storage.
     *
     * @param region the AWS region code
     * @return the S3 Standard storage price per GB per month in USD
     */
    public double getS3PricePerGb(String region) {
        return getServiceRateOrDefault(region, "s3PerGb", 0.023);
    }

    /**
     * Estimates the monthly cost of EFS storage.
     *
     * @param sizeGb the total EFS storage in gigabytes
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getEfsPrice(double sizeGb, String region) {
        return round(sizeGb * getServiceRateOrDefault(region, "efsPerGb", 0.30));
    }

    /**
     * Returns the per-GB monthly cost for ECR image storage.
     *
     * @param region the AWS region code
     * @return the ECR storage price per GB per month in USD
     */
    public double getEcrPricePerGb(String region) {
        return getServiceRateOrDefault(region, "ecrPerGb", 0.10);
    }

    // ── Networking ──

    /**
     * Estimates the monthly cost of an Elastic Load Balancer. Constructs a dynamic key
     * (e.g. {@code "elbApplicationHourly"}) to look up the hourly rate from the services section.
     *
     * @param type   the load balancer type: {@code "application"}, {@code "network"}, or {@code "classic"}
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getElbPrice(String type, String region) {
        String key = "elb" + capitalize(type) + "Hourly";
        return round(getServiceRateOrDefault(region, key, 0.0225) * HOURS_PER_MONTH);
    }

    /**
     * Returns the monthly cost of an unattached Elastic IP address.
     *
     * @param region the AWS region code
     * @return the monthly Elastic IP cost in USD
     */
    public double getElasticIpPrice(String region) {
        return getServiceRateOrDefault(region, "elasticIpMonthly", 3.65);
    }

    /**
     * Estimates the monthly cost of a NAT Gateway (hourly charge only, excludes data processing).
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getNatGatewayPrice(String region) {
        return round(getServiceRateOrDefault(region, "natGatewayHourly", 0.045) * HOURS_PER_MONTH);
    }

    /**
     * Estimates the monthly cost of an AWS Transfer Family server endpoint.
     *
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getTransferFamilyPrice(String region) {
        return round(getServiceRateOrDefault(region, "transferFamilyHourly", 0.30) * HOURS_PER_MONTH);
    }

    /**
     * Returns the monthly cost of a Route 53 hosted zone.
     *
     * @param region the AWS region code
     * @return the monthly hosted zone cost in USD
     */
    public double getRoute53HostedZonePrice(String region) {
        return getServiceRateOrDefault(region, "route53ZoneMonthly", 0.50);
    }

    // ── Database ──

    /**
     * Estimates the monthly cost of a DynamoDB table in provisioned capacity mode.
     *
     * @param rcu    the provisioned read capacity units
     * @param wcu    the provisioned write capacity units
     * @param region the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getDynamoDbProvisionedPrice(long rcu, long wcu, String region) {
        double rcuHourly = getServiceRateOrDefault(region, "dynamoRcuHourly", 0.00065);
        double wcuHourly = getServiceRateOrDefault(region, "dynamoWcuHourly", 0.00065);
        return round((rcu * rcuHourly + wcu * wcuHourly) * HOURS_PER_MONTH);
    }

    /**
     * Estimates the monthly cost of a DynamoDB table in on-demand capacity mode.
     * Projects daily consumed reads/writes over a 30-day month.
     *
     * @param consumedReads  the daily consumed read request units
     * @param consumedWrites the daily consumed write request units
     * @param region         the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getDynamoDbOnDemandPrice(double consumedReads, double consumedWrites, String region) {
        double readRate = getServiceRateOrDefault(region, "dynamoOnDemandReadPerUnit", 0.00000025);
        double writeRate = getServiceRateOrDefault(region, "dynamoOnDemandWritePerUnit", 0.00000125);
        return round((consumedReads * readRate + consumedWrites * writeRate) * 30.0);
    }

    /**
     * Estimates the monthly cost of Kinesis Data Streams shards.
     *
     * @param shardCount the number of open shards
     * @param region     the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getKinesisShardPrice(int shardCount, String region) {
        return round(shardCount * getServiceRateOrDefault(region, "kinesisShardHourly", 0.015) * HOURS_PER_MONTH);
    }

    // ── Serverless & Messaging ──

    /**
     * Estimates the monthly cost of a Lambda function based on invocation count, duration, and memory.
     * Combines the per-request cost and the per-GB-second compute cost.
     *
     * @param invocations   the number of invocations per month
     * @param avgDurationMs the average invocation duration in milliseconds
     * @param memoryMb      the configured memory in megabytes
     * @param region        the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getLambdaPrice(long invocations, long avgDurationMs, int memoryMb, String region) {
        double requestCost = getServiceRateOrDefault(region, "lambdaRequestCost", 0.0000002);
        double gbSecondCost = getServiceRateOrDefault(region, "lambdaGbSecondCost", 0.0000166667);
        double gbSeconds = invocations * (avgDurationMs / 1000.0) * (memoryMb / 1024.0);
        return round(invocations * requestCost + gbSeconds * gbSecondCost);
    }

    /**
     * Estimates the monthly cost of an SQS queue based on message volume.
     *
     * @param messagesPerMonth the number of messages processed per month
     * @param region           the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getSqsPrice(long messagesPerMonth, String region) {
        return round(messagesPerMonth * getServiceRateOrDefault(region, "sqsPerRequest", 0.0000004));
    }

    /**
     * Estimates the monthly cost of an SNS topic based on publish volume.
     *
     * @param publishesPerMonth the number of publish operations per month
     * @param region            the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getSnsPrice(long publishesPerMonth, String region) {
        return round(publishesPerMonth * getServiceRateOrDefault(region, "snsPerPublish", 0.0000005));
    }

    /**
     * Estimates the monthly cost of an API Gateway based on daily request volume.
     * Projects daily requests over 30 days and applies the per-million-requests rate.
     *
     * @param requestsPerDay the average number of API requests per day
     * @param region         the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getApiGatewayPrice(double requestsPerDay, String region) {
        double perMillion = getServiceRateOrDefault(region, "apiGatewayPerMillionRequests", 3.50);
        double monthlyRequests = requestsPerDay * 30.0;
        return round(monthlyRequests / 1_000_000.0 * perMillion);
    }

    /**
     * Estimates the monthly cost of Step Functions state machine executions.
     * Assumes approximately 10 state transitions per execution and projects over 30 days.
     *
     * @param executionsPerDay the average number of executions per day
     * @param region           the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getStepFunctionsPrice(double executionsPerDay, String region) {
        double perTransition = getServiceRateOrDefault(region, "stepFunctionsPerTransition", 0.000025);
        double monthlyTransitions = executionsPerDay * 30.0 * 10.0; // ~10 transitions per execution
        return round(monthlyTransitions * perTransition);
    }

    /**
     * Estimates the monthly cost of AWS Glue jobs based on DPU-hours consumed.
     *
     * @param dpuHours the total DPU-hours consumed per month
     * @param region   the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getGlueDpuPrice(double dpuHours, String region) {
        double rate = getServiceRateOrDefault(region, "glueDpuHourly", 0.44);
        return round(dpuHours * rate);
    }

    // ── Security & Monitoring ──

    /**
     * Returns the monthly cost of a KMS customer-managed key.
     *
     * @param region the AWS region code
     * @return the monthly key cost in USD
     */
    public double getKmsKeyPrice(String region) {
        return getServiceRateOrDefault(region, "kmsKeyMonthly", 1.00);
    }

    /**
     * Estimates the monthly cost of CloudWatch standard alarms.
     *
     * @param count  the number of alarms
     * @param region the AWS region code
     * @return the estimated monthly cost in USD
     */
    public double getCloudWatchAlarmPrice(int count, String region) {
        return count * getServiceRateOrDefault(region, "cwAlarmMonthly", 0.10);
    }

    /**
     * Estimates the monthly cost of CloudWatch Logs storage.
     *
     * @param storedGb the total stored log data in gigabytes
     * @param region   the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getCloudWatchLogStoragePrice(double storedGb, String region) {
        return round(storedGb * getServiceRateOrDefault(region, "cwLogStoragePerGb", 0.03));
    }

    /**
     * Returns the monthly cost of a Secrets Manager secret.
     *
     * @param region the AWS region code
     * @return the monthly secret cost in USD
     */
    public double getSecretPrice(String region) {
        return getServiceRateOrDefault(region, "secretMonthly", 0.40);
    }

    /**
     * Returns the monthly cost of an SSM advanced parameter.
     *
     * @param region the AWS region code
     * @return the monthly advanced parameter cost in USD
     */
    public double getAdvancedParameterPrice(String region) {
        return getServiceRateOrDefault(region, "advancedParamMonthly", 0.05);
    }

    /**
     * Estimates the monthly cost of a WAF Web ACL including its rules.
     * Combines the per-ACL monthly charge with a per-rule monthly charge.
     *
     * @param ruleCount the number of rules attached to the Web ACL
     * @param region    the AWS region code
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    public double getWafPrice(int ruleCount, String region) {
        double aclMonthly = getServiceRateOrDefault(region, "wafAclMonthly", 5.0);
        double ruleMonthly = getServiceRateOrDefault(region, "wafRuleMonthly", 1.0);
        return round(aclMonthly + ruleMonthly * ruleCount);
    }

    /**
     * Returns the monthly cost of a Managed Grafana workspace.
     *
     * @param region the AWS region code
     * @return the monthly workspace cost in USD
     */
    public double getGrafanaWorkspacePrice(String region) {
        return getServiceRateOrDefault(region, "grafanaWorkspaceMonthly", 9.0);
    }

    // ── Internal helpers ──

    /**
     * Computes the monthly cost for an instance-based service using a two-stage lookup:
     * <ol>
     *   <li>Exact match: looks up the hourly rate for the specific instance type in the region.</li>
     *   <li>Size multiplier: if no exact match, uses the {@code _baseLargeHourly} rate for the
     *       service/region and scales it by {@link #sizeMultiplier(String)}.</li>
     * </ol>
     *
     * @param service          the service category key in the pricing JSON (e.g. {@code "ec2"}, {@code "rds"})
     * @param instanceType     the full instance type string (e.g. {@code "m5.large"}, {@code "db.t3.micro"})
     * @param region           the AWS region code
     * @param defaultBaseLarge the fallback hourly rate for a "large" instance if no regional data exists
     * @return the estimated monthly cost in USD, rounded to 2 decimal places
     */
    private double instancePrice(String service, String instanceType, String region, double defaultBaseLarge) {
        double hourly = pricingData.getInstanceRate(service, region, instanceType);
        if (hourly > 0) return round(hourly * HOURS_PER_MONTH);
        double baseLarge = pricingData.getBaseLargeHourly(service, region);
        if (baseLarge <= 0) baseLarge = defaultBaseLarge;
        return round(sizeMultiplier(instanceType) * baseLarge * HOURS_PER_MONTH);
    }

    /**
     * Retrieves a service-level rate from the pricing data, returning a default if not found.
     *
     * @param region       the AWS region code
     * @param key          the rate key in the services section (e.g. {@code "natGatewayHourly"}, {@code "s3PerGb"})
     * @param defaultValue the fallback value to use if the key is not present in the pricing data
     * @return the rate from the pricing data, or {@code defaultValue} if not found or non-positive
     */
    private double getServiceRateOrDefault(String region, String key, double defaultValue) {
        double rate = pricingData.getServiceRate(region, key);
        return rate > 0 ? rate : defaultValue;
    }

    /**
     * Extracts a size multiplier from an instance type string, relative to a "large" instance (1.0x).
     * Works for any prefix: {@code db.}, {@code ml.}, {@code cache.}, etc. The multiplier is derived
     * from the size suffix in the instance type name and follows the standard AWS instance size
     * progression: nano (0.0625x) through 24xlarge/metal (48x).
     *
     * @param instanceType the full instance type string (e.g. {@code "m5.xlarge"}, {@code "db.t3.micro"})
     * @return the size multiplier relative to "large" (1.0x), defaults to 1.0 for unrecognized sizes
     */
    static double sizeMultiplier(String instanceType) {
        if (instanceType == null || instanceType.isBlank()) return 1.0;
        String lower = instanceType.toLowerCase();
        if (lower.contains(".nano")) return 0.0625;
        if (lower.contains(".micro")) return 0.125;
        if (lower.contains(".small")) return 0.25;
        if (lower.contains(".medium")) return 0.5;
        if (lower.contains(".24xlarge") || lower.contains(".metal")) return 48.0;
        if (lower.contains(".16xlarge")) return 32.0;
        if (lower.contains(".12xlarge")) return 24.0;
        if (lower.contains(".8xlarge")) return 16.0;
        if (lower.contains(".4xlarge")) return 8.0;
        if (lower.contains(".2xlarge")) return 4.0;
        if (lower.contains(".xlarge")) return 2.0;
        if (lower.contains(".large")) return 1.0;
        return 1.0;
    }

    /**
     * Capitalizes the first letter of a string and lowercases the rest.
     * Used to construct dynamic pricing key names (e.g. {@code "gp3"} becomes {@code "Gp3"}).
     *
     * @param s the input string
     * @return the capitalized string, or the original if null/empty
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /**
     * Rounds a value to 2 decimal places using standard rounding.
     *
     * @param value the value to round
     * @return the value rounded to 2 decimal places
     */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
