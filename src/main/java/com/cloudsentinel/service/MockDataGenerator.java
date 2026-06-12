package com.cloudsentinel.service;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.Severity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates realistic mock AWS resource data for demo/showcase mode.
 *
 * <p>Produces a believable mix of healthy, idle, and problematic resources across
 * all finding types (Cost, Security, Governance) and severity levels. The generated
 * data exercises the full pipeline: correlation engine, severity classification,
 * dashboard tabs, charts, and AI analysis.</p>
 *
 * <p>Activated by the {@code mock-data} Spring profile: {@code --spring.profiles.active=mock-data}</p>
 */
public final class MockDataGenerator {

    private MockDataGenerator() {}

    private static final Random RNG = new Random(42); // deterministic for consistent demos
    private static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-southeast-1"};
    private static final String DEMO_ACCOUNT = "123456789012";

    /**
     * Generates a full set of mock resources representing a realistic AWS account.
     *
     * @param regions the regions to distribute resources across (empty = use defaults)
     * @param category scan category filter: "COST_OPTIMIZATION", "SECURITY_GOVERNANCE", or null/FULL
     * @return a list of mock ResourceDto objects with recommendations, severity, and finding types
     */
    public static List<ResourceDto> generate(List<String> regions, String category) {
        String[] activeRegions = (regions != null && !regions.isEmpty())
                ? regions.toArray(String[]::new) : REGIONS;
        // Helper to safely distribute across available regions
        java.util.function.IntFunction<String> r = i -> activeRegions[i % activeRegions.length];
        var resources = new ArrayList<ResourceDto>();

        boolean includeCost = category == null || "FULL".equalsIgnoreCase(category) || "COST_OPTIMIZATION".equalsIgnoreCase(category);
        boolean includeSecurity = category == null || "FULL".equalsIgnoreCase(category) || "SECURITY_GOVERNANCE".equalsIgnoreCase(category);

        if (includeCost) {
            // EC2 instances — mix of states
            resources.add(ec2("i-0a1b2c3d4e5f6789a", "web-server-prod", "m5.xlarge", "running", 72.5, 156.80, r.apply(0), days(365)));
            resources.add(ec2("i-0b2c3d4e5f6789abc", "api-server-prod", "c5.2xlarge", "running", 45.2, 280.00, r.apply(0), days(200)));
            resources.add(ec2("i-0c3d4e5f67890bcde", "dev-test-box", "t3.medium", "stopped", 0, 0, r.apply(0), days(120)));
            resources.add(ec2("i-0d4e5f678901cdef0", "jenkins-ci", "t3.large", "running", 2.1, 61.00, r.apply(1), days(90)));
            resources.add(ec2("i-0e5f6789012defab1", "staging-app", "m5.large", "stopped", 0, 0, r.apply(1), days(60)));
            resources.add(ec2("i-0f67890123efabcd2", "ml-training", "p3.2xlarge", "running", 8.5, 920.00, r.apply(0), days(30)));
            resources.add(ec2("i-0a789012345fabcde", "bastion-host", "t3.micro", "running", 0.5, 7.60, r.apply(2), days(400)));

            // RDS instances
            resources.add(rds("db-prod-postgres", "db.r5.xlarge", "available", 35.0, 450.00, r.apply(0), days(300)));
            resources.add(rds("db-staging-mysql", "db.t3.medium", "available", 3.2, 65.00, r.apply(1), days(180)));
            resources.add(rds("db-dev-unused", "db.t3.small", "stopped", 0, 0, r.apply(1), days(90)));

            // EBS volumes
            resources.add(ebs("vol-0abc123def456789a", "gp3 / 100GB", "in-use", 8.00, r.apply(0)));
            resources.add(ebs("vol-0bcd234efa567890b", "gp3 / 500GB", "available", 40.00, r.apply(0)));
            resources.add(ebs("vol-0cde345fab678901c", "io2 / 200GB", "available", 65.00, r.apply(1)));

            // S3 buckets
            resources.add(s3("company-data-lake", 2_500_000, 1200.50, true));
            resources.add(s3("old-backups-2022", 50_000, 45.00, false));
            resources.add(s3("empty-test-bucket", 0, 0, true));

            // Lambda functions
            resources.add(lambda("data-processor", 500, 12.00, r.apply(0)));
            resources.add(lambda("legacy-webhook", 0, 0, r.apply(1)));
            resources.add(lambda("auth-validator", 15000, 85.00, r.apply(0)));

            // NAT Gateways
            resources.add(natGateway("nat-0abc123def456", 50_000_000, 32.50, r.apply(0)));
            resources.add(natGateway("nat-0bcd234efa567", 500, 32.50, r.apply(2)));

            // Elastic IPs
            resources.add(elasticIp("eipalloc-0abc123", true, r.apply(0)));
            resources.add(elasticIp("eipalloc-0bcd234", false, r.apply(1)));

            // ELB
            resources.add(elb("app/prod-alb/abc123", "Application Load Balancer", 22.00, r.apply(0)));

            // ElastiCache
            resources.add(elastiCache("redis-prod", "cache.r5.large", 28.5, 180.00, r.apply(0)));
            resources.add(elastiCache("redis-dev", "cache.t3.micro", 1.2, 12.50, r.apply(1)));

            // DynamoDB
            resources.add(dynamoDb("UserSessions", 150.0, 80.0, 35.00, r.apply(0)));
            resources.add(dynamoDb("AuditLog", 0.1, 0.05, 25.00, r.apply(0)));

            // SQS
            resources.add(sqs("order-processing-queue", 5000, r.apply(0)));
            resources.add(sqs("dead-letter-queue", 0, r.apply(0)));
        }

        if (includeSecurity) {
            // IAM Users
            resources.add(iamUser("admin-user", true, 5));
            resources.add(iamUser("deploy-bot", false, 10));
            resources.add(iamUser("legacy-service-account", false, 120));
            resources.add(iamUser("intern-2023", true, 200));

            // IAM Roles
            resources.add(iamRole("LambdaExecutionRole", 2));
            resources.add(iamRole("OldCICDRole", 150));

            // KMS Keys
            resources.add(kmsKey("alias/prod-encryption", true, "Enabled"));
            resources.add(kmsKey("alias/old-data-key", false, "Enabled"));
            resources.add(kmsKey("alias/deprecated-key", false, "Disabled"));

            // ACM Certificates
            resources.add(acmCert("arn:aws:acm:us-east-1:123:cert/abc", "*.example.com", "ISSUED", true));
            resources.add(acmCert("arn:aws:acm:us-east-1:123:cert/def", "old.example.com", "EXPIRED", false));

            // CloudTrail
            resources.add(cloudTrail("management-trail", true, r.apply(0)));
            resources.add(cloudTrail("data-events-trail", false, r.apply(1)));

            // Secrets Manager
            resources.add(secret("prod/db-password", 5, true));
            resources.add(secret("dev/api-key", 120, false));

            // WAF
            resources.add(waf("prod-web-acl", true, 5, r.apply(0)));
            resources.add(waf("empty-acl", true, 0, r.apply(0)));

            // CloudWatch
            resources.add(cwAlarm("HighCPUAlarm", "OK", r.apply(0)));
            resources.add(cwAlarm("OrphanedAlarm", "INSUFFICIENT_DATA", r.apply(0)));
            resources.add(cwLogGroup("/aws/lambda/data-processor", 30, r.apply(0)));
            resources.add(cwLogGroup("/aws/lambda/legacy-webhook", null, r.apply(1)));

            // CloudFormation
            resources.add(cfnStack("prod-infrastructure", "CREATE_COMPLETE", 25, r.apply(0)));
            resources.add(cfnStack("failed-deployment", "ROLLBACK_COMPLETE", 3, r.apply(1)));
            resources.add(cfnStack("stuck-delete", "DELETE_FAILED", 8, r.apply(0)));

            // SSM Parameters
            resources.add(ssmParam("/config/db-host", "String", false, 30));
            resources.add(ssmParam("/config/api-secret", "String", true, 200));

            // VPC
            resources.add(vpc("vpc-0abc123", 5, r.apply(0)));
            resources.add(vpc("vpc-0bcd234", 0, r.apply(2)));
        }

        return resources;
    }

    // ── Factory methods for each resource type ──

    private static ResourceDto ec2(String id, String name, String type, String state, double cpu, double cost, String region, String created) {
        var dto = base(region, "EC2", id, name, type, state, cost, created);
        dto.setCpuUtilizationAvg(cpu);
        dto.setFindingType(FindingType.COST);
        if ("stopped".equals(state)) {
            dto.setRecommendation("Consider Terminating - Stopped");
            dto.setRecommendationDetail("Instance has been stopped. EBS volumes continue to incur charges.");
        } else if (cpu < RecommendationEngine.IDLE_CPU_PERCENT) {
            dto.setRecommendation("Idle - Consider Downsizing or Terminating");
            dto.setRecommendationDetail(String.format("CPU utilization is %.1f%% (7-day avg). Consider downsizing or terminating.", cpu));
        } else if (cpu < RecommendationEngine.LOW_CPU_PERCENT) {
            dto.setRecommendation("Low Utilization - Consider Downsizing");
        } else if (cpu < RecommendationEngine.MODERATE_CPU_PERCENT) {
            dto.setRecommendation("Moderate Utilization");
        } else {
            dto.setRecommendation("Active - Good Utilization");
        }
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto rds(String id, String type, String state, double cpu, double cost, String region, String created) {
        var dto = base(region, "RDS", id, id, type, state, cost, created);
        dto.setCpuUtilizationAvg(cpu);
        dto.setFindingType(FindingType.COST);
        if ("stopped".equals(state)) dto.setRecommendation("Consider Terminating - Stopped");
        else if (cpu < 5) dto.setRecommendation("Idle - Consider Downsizing or Terminating");
        else if (cpu < 20) dto.setRecommendation("Low Utilization - Consider Downsizing");
        else dto.setRecommendation("Active - Good Utilization");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto ebs(String id, String type, String state, double cost, String region) {
        var dto = base(region, "EBS", id, id, type, state, cost, days(180));
        dto.setFindingType(FindingType.COST);
        dto.setRecommendation("available".equals(state) ? "Delete - Unattached" : "In Use");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto s3(String name, long objects, double cost, boolean recentAccess) {
        var dto = base("us-east-1", "S3", name, name, String.format("%.2f GB / %d objects", cost / 10.0, objects), "active", cost, days(500));
        dto.setFindingType(FindingType.COST);
        if (objects == 0) dto.setRecommendation("Empty - Consider Deleting");
        else if (!recentAccess) dto.setRecommendation("Inactive - No Recent Access");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto lambda(String name, double invocationsPerDay, double cost, String region) {
        var dto = base(region, "Lambda", name, name, "nodejs18.x / 256MB", "active", cost, days(120));
        dto.setFindingType(FindingType.COST);
        if (invocationsPerDay < 1) dto.setRecommendation("Idle - No Invocations");
        else if (invocationsPerDay < 10) dto.setRecommendation("Low Utilization");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto natGateway(String id, double bytesOut, double cost, String region) {
        var dto = base(region, "NAT Gateway", id, id, "NAT Gateway", "available", cost, days(200));
        dto.setFindingType(FindingType.COST);
        if (bytesOut < 1024) dto.setRecommendation("Idle - Low Traffic");
        else if (bytesOut < 1_048_576) dto.setRecommendation("Low Utilization - Consider Downsizing");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto elasticIp(String id, boolean associated, String region) {
        var dto = base(region, "Elastic IP", id, id, associated ? "i-attached" : "unattached", associated ? "associated" : "available", 3.65, days(300));
        dto.setFindingType(FindingType.COST);
        dto.setRecommendation(associated ? "In Use" : "Release - Unattached");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto elb(String id, String type, double cost, String region) {
        var dto = base(region, type, id, "prod-alb", "application", "active", cost, days(250));
        dto.setFindingType(FindingType.COST);
        dto.setRecommendation("Review Usage");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto elastiCache(String id, String type, double cpu, double cost, String region) {
        var dto = base(region, "ElastiCache", id, id, type, "available", cost, days(180));
        dto.setCpuUtilizationAvg(cpu);
        dto.setFindingType(FindingType.COST);
        if (cpu < 5) dto.setRecommendation("Idle - Consider Downsizing or Terminating");
        else dto.setRecommendation("Active - Good Utilization");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto dynamoDb(String name, double reads, double writes, double cost, String region) {
        var dto = base(region, "DynamoDB", name, name, "Provisioned", "active", cost, days(400));
        dto.setFindingType(FindingType.COST);
        if (reads < 1 && writes < 1) dto.setRecommendation("Idle - No Read/Write Activity");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto sqs(String name, double messagesPerDay, String region) {
        var dto = base(region, "SQS", name, name, "Standard", "active", 0.50, days(200));
        dto.setFindingType(FindingType.COST);
        if (messagesPerDay < 1) dto.setRecommendation("Idle - No Messages Sent");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto iamUser(String name, boolean mfa, int daysSinceLogin) {
        var dto = base("global", "IAM User", name, name, "IAM User", "active", 0, days(daysSinceLogin + 30));
        dto.setFindingType(FindingType.SECURITY);
        if (daysSinceLogin > RecommendationEngine.INACTIVITY_DAYS) dto.setRecommendation("Unused - Inactive > 90 Days");
        else if (!mfa) dto.setRecommendation("Enable MFA - User Lacks Multi-Factor Authentication");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto iamRole(String name, int daysSinceUsed) {
        var dto = base("global", "IAM Role", name, name, "IAM Role", "active", 0, days(daysSinceUsed + 60));
        dto.setFindingType(FindingType.SECURITY);
        if (daysSinceUsed > 90) dto.setRecommendation("Unused - Not Used > 90 Days");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto kmsKey(String id, boolean rotation, String state) {
        var dto = base("us-east-1", "KMS", id, id, "Symmetric / AES-256", state.toLowerCase(), 1.00, days(500));
        dto.setFindingType(FindingType.SECURITY);
        if (!"Enabled".equals(state)) dto.setRecommendation("Stale - Key " + state);
        else if (!rotation) dto.setRecommendation("Rotate Key - Auto-Rotation Disabled");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto acmCert(String arn, String domain, String status, boolean inUse) {
        var dto = base("us-east-1", "ACM Certificate", arn, domain, "Amazon Issued / RSA-2048", status.toLowerCase(), 0, days(365));
        dto.setFindingType(FindingType.SECURITY);
        if ("EXPIRED".equals(status)) dto.setRecommendation("Expired - Certificate Needs Renewal");
        else if (!inUse) dto.setRecommendation("Unused - Certificate Not Attached");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto cloudTrail(String name, boolean logging, String region) {
        var dto = base(region, "CloudTrail", name, name, "Management Events", logging ? "logging" : "not-logging", 0, days(500));
        dto.setFindingType(FindingType.SECURITY);
        dto.setRecommendation(logging ? "Active" : "Enable Logging - CloudTrail Not Recording");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto secret(String name, int daysSinceAccessed, boolean rotation) {
        var dto = base("us-east-1", "Secrets Manager", name, name, "SecureString", "active", 0.40, days(daysSinceAccessed + 30));
        dto.setFindingType(FindingType.SECURITY);
        if (daysSinceAccessed > 90) dto.setRecommendation("Unused - Not Accessed > 90 Days");
        else if (!rotation) dto.setRecommendation("Rotate Secret - Auto-Rotation Disabled");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto waf(String id, boolean associated, int rules, String region) {
        var dto = base(region, "WAF", id, id, "Regional / " + rules + " rules", "active", 5.00 + rules, days(200));
        dto.setFindingType(FindingType.SECURITY);
        if (!associated) dto.setRecommendation("Unused - WAF Not Associated with Any Resource");
        else if (rules == 0) dto.setRecommendation("Misconfigured - WAF Has No Rules");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto cwAlarm(String name, String state, String region) {
        var dto = base(region, "CloudWatch Alarm", name, name, "Metric Alarm", state.toLowerCase(), 0.10, days(300));
        dto.setFindingType(FindingType.GOVERNANCE);
        if ("INSUFFICIENT_DATA".equals(state)) dto.setRecommendation("Stale - Alarm Has Insufficient Data");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto cwLogGroup(String name, Integer retentionDays, String region) {
        var dto = base(region, "CloudWatch Log Group", name, name, retentionDays != null ? retentionDays + " days retention" : "No retention", "active", 2.50, days(400));
        dto.setFindingType(FindingType.GOVERNANCE);
        dto.setRecommendation(retentionDays == null ? "Missing Retention Policy - Logs Never Expire" : "Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto cfnStack(String name, String status, int resources, String region) {
        var dto = base(region, "CloudFormation", name + "-stack-id", name, status + " / " + resources + " resources", status.toLowerCase().replace('_', '-'), 0, days(250));
        dto.setFindingType(FindingType.GOVERNANCE);
        if (status.contains("ROLLBACK")) dto.setRecommendation("Stale - Stack in Rollback State");
        else if (status.contains("DELETE_FAILED")) dto.setRecommendation("Stale - Stack Delete Failed");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto ssmParam(String name, String type, boolean sensitive, int daysSinceModified) {
        var dto = base("us-east-1", "SSM Parameter", name, name, type, "active", 0, days(daysSinceModified));
        dto.setFindingType(FindingType.GOVERNANCE);
        if (sensitive && "String".equals(type)) dto.setRecommendation("Exposed - Sensitive Value Stored as Plain String");
        else if (daysSinceModified > RecommendationEngine.STALE_DAYS) dto.setRecommendation("Stale - Not Modified > 180 Days");
        else dto.setRecommendation("Active");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    private static ResourceDto vpc(String id, int instanceCount, String region) {
        var dto = base(region, "VPC", id, id, "10.0.0.0/16", instanceCount > 0 ? "active" : "empty", 0, days(500));
        dto.setFindingType(FindingType.GOVERNANCE);
        dto.setRecommendation(instanceCount == 0 ? "Unused - No Running Instances" : "In Use");
        dto.setSeverity(ResourceAnalyzer.classifySeverity(dto));
        return dto;
    }

    // ── Helpers ──

    private static ResourceDto base(String region, String type, String id, String name, String instanceType, String state, double cost, String created) {
        var dto = new ResourceDto();
        dto.setRegion(region);
        dto.setResourceType(type);
        dto.setResourceId(id);
        dto.setResourceName(name);
        dto.setInstanceType(instanceType);
        dto.setState(state);
        dto.setMonthlyCostUsd(cost);
        dto.setCreatedDate(created);
        return dto;
    }

    private static String days(int daysAgo) {
        return Instant.now().minus(daysAgo, ChronoUnit.DAYS).toString();
    }

    /** Returns the demo account ID used for mock data. */
    public static String getDemoAccountId() { return DEMO_ACCOUNT; }

    /**
     * Generates realistic pre-baked AI insights for demo mode.
     * Avoids the need for a running AI model to showcase the AI-powered experience.
     *
     * @param resources the mock resources to reference in AI insights
     * @return a fully populated {@link com.cloudsentinel.dto.AiInsightDto} with hardcoded insights
     */
    public static com.cloudsentinel.dto.AiInsightDto generateMockAiInsights(List<ResourceDto> resources) {
        var actions = new ArrayList<com.cloudsentinel.dto.AiInsightDto.ActionItem>();

        // Prioritized actions referencing actual mock resource IDs
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "i-0f67890123efabcd2", "EC2", "us-east-1", "REVIEW",
                "This p3.2xlarge ML training instance is running at only 8.5% CPU utilization. At $920/mo, this is the single largest cost item. Consider using Spot Instances for training workloads or scheduling it to run only during active training hours.",
                "Medium", 690.00));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "i-0c3d4e5f67890bcde", "EC2", "us-east-1", "TERMINATE",
                "This dev/test instance has been stopped for 120 days. The attached EBS volumes continue to incur charges. If no longer needed, terminate the instance and delete associated volumes.",
                "Low", 0));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "i-0d4e5f678901cdef0", "EC2", "us-west-2", "DOWNSIZE",
                "Jenkins CI server running at 2.1% CPU on t3.large ($61/mo). A t3.small would handle this workload at roughly one-third the cost.",
                "Low", 40.00));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "vol-0bcd234efa567890b", "EBS", "us-east-1", "TERMINATE",
                "Unattached 500GB gp3 volume costing $40/mo. Snapshot it for backup if needed, then delete.",
                "Low", 40.00));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "vol-0cde345fab678901c", "EBS", "us-west-2", "TERMINATE",
                "Unattached 200GB io2 volume costing $65/mo. High-performance storage sitting idle is particularly wasteful.",
                "Low", 65.00));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "eipalloc-0bcd234", "Elastic IP", "us-west-2", "TERMINATE",
                "Unassociated Elastic IP incurring $3.65/mo. AWS charges for all unattached public IPv4 addresses since February 2024.",
                "Low", 3.65));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "legacy-webhook", "Lambda", "us-west-2", "REVIEW",
                "This Lambda function has zero invocations. If the upstream integration has been decommissioned, the function and its IAM role can be safely deleted.",
                "Low", 0));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "nat-0bcd234efa567", "NAT Gateway", "eu-west-1", "REVIEW",
                "This NAT Gateway processes negligible traffic (500 bytes) but costs $32.50/mo. If the associated private subnets no longer need internet access, consider removing it.",
                "Medium", 32.50));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "intern-2023", "IAM User", "global", "REMEDIATE",
                "This IAM user has MFA enabled but hasn't logged in for 200 days. Credentials should be deactivated and the user removed from all groups.",
                "Low", 0));
        actions.add(new com.cloudsentinel.dto.AiInsightDto.ActionItem(
                "data-events-trail", "CloudTrail", "us-west-2", "ENABLE",
                "CloudTrail is not recording events in this region. This creates a critical visibility gap for security auditing and incident response.",
                "High", 0));

        var rightSizing = List.of(
                new com.cloudsentinel.dto.AiInsightDto.RightSizingSuggestion(
                        "i-0b2c3d4e5f6789abc", "c5.2xlarge", "c5.xlarge",
                        280.00, 140.00,
                        "API server running at 45% CPU. The workload pattern shows consistent headroom — a c5.xlarge provides sufficient capacity with a 50% cost reduction."),
                new com.cloudsentinel.dto.AiInsightDto.RightSizingSuggestion(
                        "redis-dev", "cache.r5.large", "cache.t3.small",
                        12.50, 4.50,
                        "Dev Redis cluster at 1.2% CPU utilization. A t3.small provides burstable performance suitable for development workloads."),
                new com.cloudsentinel.dto.AiInsightDto.RightSizingSuggestion(
                        "db-staging-mysql", "db.t3.medium", "db.t3.small",
                        65.00, 32.50,
                        "Staging RDS instance at 3.2% CPU. A t3.small is sufficient for staging workloads and halves the monthly cost.")
        );

        var architectureInsights = List.of(
                new com.cloudsentinel.dto.AiInsightDto.ArchitectureInsight(
                        "Cost Optimization",
                        "The ML training instance (p3.2xlarge) accounts for 30% of total compute cost but runs at under 10% utilization.",
                        "Consider Spot Instances, SageMaker managed training, or scheduled start/stop to reduce waste."),
                new com.cloudsentinel.dto.AiInsightDto.ArchitectureInsight(
                        "Security",
                        "CloudTrail is disabled in us-west-2, and one IAM user hasn't logged in for 200 days.",
                        "Enable CloudTrail in all regions and implement an automated IAM credential rotation policy."),
                new com.cloudsentinel.dto.AiInsightDto.ArchitectureInsight(
                        "Reliability",
                        "Two unattached EBS volumes suggest incomplete cleanup after instance termination.",
                        "Implement lifecycle policies or automated cleanup scripts to prevent orphaned resources.")
        );

        var wellArchitected = List.of(
                new com.cloudsentinel.dto.AiInsightDto.WellArchitected(
                        "Cost Optimization",
                        "Multiple idle and underutilized resources detected across compute, storage, and networking.",
                        "Estimated $871/mo in recoverable spend across 10 actionable resources. Implement tagging and cost allocation to track ownership."),
                new com.cloudsentinel.dto.AiInsightDto.WellArchitected(
                        "Security",
                        "IAM hygiene gaps: inactive users with active credentials, CloudTrail disabled in secondary region.",
                        "Enable CloudTrail in all regions, enforce MFA universally, and implement 90-day credential rotation."),
                new com.cloudsentinel.dto.AiInsightDto.WellArchitected(
                        "Operational Excellence",
                        "Orphaned resources (EBS volumes, empty S3 buckets, failed CloudFormation stacks) indicate manual cleanup gaps.",
                        "Implement automated resource lifecycle management and tag-based governance policies.")
        );

        var cleanupPlan = List.of(
                new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                        "Phase 1: Quick Wins (Week 1)",
                        "Release unattached Elastic IP, delete unattached EBS volumes, remove empty S3 bucket, delete orphaned Lambda function.",
                        4, 108.65, "Low"),
                new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                        "Phase 2: Right-Sizing (Week 2)",
                        "Downsize Jenkins CI to t3.small, downsize API server to c5.xlarge, downsize staging RDS and dev Redis.",
                        4, 180.50, "Low"),
                new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                        "Phase 3: Architecture Changes (Week 3-4)",
                        "Migrate ML training to Spot/SageMaker, remove idle NAT Gateway, terminate long-stopped dev instances.",
                        3, 722.50, "Medium"),
                new com.cloudsentinel.dto.AiInsightDto.CleanupPhase(
                        "Phase 4: Security Remediation (Ongoing)",
                        "Enable CloudTrail in all regions, deactivate inactive IAM users, enforce auto-rotation on Secrets Manager, add WAF rules to empty ACL.",
                        5, 0, "Low")
        );

        var aiUsage = new com.cloudsentinel.dto.AiUsageDto(
                "demo", "mock-ai-v1 (pre-generated insights)", 3200, 1850, 5050,
                2400, 12500, 7200, 45.0,
                "These insights were pre-generated for demo mode — no AI model was called.");

        return new com.cloudsentinel.dto.AiInsightDto(
                "This AWS account has 50 resources across 5 regions with an estimated monthly spend of $3,700. "
                + "Analysis identified 32 actionable findings: 10 resources are candidates for immediate cost savings totaling ~$871/mo "
                + "(23% of total spend). Key concerns include an underutilized p3.2xlarge ML instance ($920/mo at 8.5% CPU), "
                + "two unattached EBS volumes ($105/mo combined), and a NAT Gateway with negligible traffic ($32.50/mo). "
                + "On the security front, CloudTrail is disabled in us-west-2 and one IAM user has been inactive for 200 days. "
                + "A phased cleanup plan is recommended, starting with low-risk quick wins in Week 1.",
                actions, rightSizing, architectureInsights, wellArchitected, cleanupPlan,
                "Primary risks: terminating the ML training instance during an active job (verify no running experiments first), "
                + "and downsizing the API server during peak traffic (monitor for 48h post-change). "
                + "All Phase 1 actions are zero-risk deletions of confirmed-idle resources.",
                "Current monthly spend: ~$3,700 across 50 resources in 5 regions. "
                + "Immediate savings opportunity: $871/mo (23%) from 10 actionable resources. "
                + "Right-sizing alone recovers $180/mo. The ML training instance is the single largest optimization target at $690/mo.",
                "demo", "mock-ai-v1 (pre-generated insights)", aiUsage
        );
    }
}
