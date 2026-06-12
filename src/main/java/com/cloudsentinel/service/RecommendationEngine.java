package com.cloudsentinel.service;

import org.springframework.stereotype.Service;

/**
 * Centralized recommendation engine that classifies resource utilization into actionable categories.
 *
 * <p>Every resource scanner delegates to this engine to generate a recommendation string that
 * determines how the resource is displayed and whether it is counted as "actionable" for cost
 * savings. The recommendation strings follow a strict naming convention where the first word(s)
 * serve as an actionability prefix matched by {@link ResourceAnalyzer#isActionable(com.cloudsentinel.dto.ResourceDto)}.</p>
 *
 * <h3>Actionable Prefixes</h3>
 * <p>Recommendations starting with any of these prefixes are considered actionable (idle/unused)
 * and contribute to the potential savings total:</p>
 * <ul>
 *   <li><strong>Idle</strong> — resource is running but performing no useful work (e.g., Lambda with zero
 *       invocations, EC2 with &lt; 5% CPU)</li>
 *   <li><strong>Delete</strong> — resource should be removed (unattached EBS, expired ACM certificate, revoked cert)</li>
 *   <li><strong>Release</strong> — billable allocation should be freed (unattached Elastic IP)</li>
 *   <li><strong>Unused</strong> — resource has not been accessed or used beyond a staleness threshold
 *       (IAM user/role inactive &gt; 90 days, Secret not accessed &gt; 90 days)</li>
 *   <li><strong>Empty</strong> — container has no contents (S3 bucket with 0 objects)</li>
 *   <li><strong>Inactive</strong> — resource exists but has had no recent access (S3 with no recent access)</li>
 *   <li><strong>Stopped</strong> — compute resource is explicitly stopped but still incurring charges</li>
 *   <li><strong>Consider Terminating</strong> — stopped EC2/RDS instance that should be evaluated for termination</li>
 * </ul>
 *
 * <h3>Security/Governance Actionable Prefixes</h3>
 * <ul>
 *   <li><strong>Enable</strong> — feature/control is disabled (e.g., Enable MFA, Enable Logging)</li>
 *   <li><strong>Rotate</strong> — credential/key needs rotation (e.g., Rotate Key, Rotate Secret)</li>
 *   <li><strong>Restrict</strong> — overly permissive access</li>
 *   <li><strong>Expired</strong> — certificate or credential has expired</li>
 *   <li><strong>Exposed</strong> — sensitive data stored insecurely</li>
 *   <li><strong>Missing</strong> — required configuration is absent (e.g., Missing Retention Policy)</li>
 *   <li><strong>Stale</strong> — resource in degraded/outdated state (e.g., Stale - Stack in Rollback)</li>
 *   <li><strong>Misconfigured</strong> — resource configured incorrectly</li>
 * </ul>
 *
 * <h3>Non-Actionable Prefixes</h3>
 * <ul>
 *   <li><strong>Active</strong> / <strong>In Use</strong> — resource is healthy and utilized</li>
 *   <li><strong>Review</strong> — requires human judgment but is not automatically counted as actionable</li>
 *   <li><strong>Low Utilization</strong> / <strong>Moderate Utilization</strong> — under-used but not idle</li>
 *   <li><strong>High Error Rate</strong> — operational concern, not a cost issue</li>
 * </ul>
 *
 * <h3>Key Thresholds</h3>
 * <table>
 *   <tr><th>Resource</th><th>Metric</th><th>Threshold</th><th>Recommendation</th></tr>
 *   <tr><td>EC2/RDS/Aurora/Redshift</td><td>CPU %</td><td>&lt; 5%</td><td>Idle</td></tr>
 *   <tr><td>EC2/RDS/Aurora/Redshift</td><td>CPU %</td><td>&lt; 20%</td><td>Low Utilization</td></tr>
 *   <tr><td>EC2/RDS/Aurora/Redshift</td><td>CPU %</td><td>&lt; 50%</td><td>Moderate Utilization</td></tr>
 *   <tr><td>IAM User</td><td>Days since login</td><td>&gt; 90 days</td><td>Unused</td></tr>
 *   <tr><td>IAM Role</td><td>Days since used</td><td>&gt; 90 days</td><td>Unused</td></tr>
 *   <tr><td>Secrets Manager</td><td>Days since accessed</td><td>&gt; 90 days</td><td>Unused</td></tr>
 *   <tr><td>SSM Parameter</td><td>Days since modified</td><td>&gt; 180 days</td><td>Review</td></tr>
 *   <tr><td>NAT Gateway</td><td>Bytes out</td><td>&lt; 1 KB</td><td>Idle</td></tr>
 *   <tr><td>NAT Gateway</td><td>Bytes out</td><td>&lt; 1 MB</td><td>Low Utilization</td></tr>
 *   <tr><td>DynamoDB</td><td>Read + Write units</td><td>&lt; 1 each</td><td>Idle</td></tr>
 *   <tr><td>SQS</td><td>Messages/day</td><td>&lt; 1</td><td>Idle</td></tr>
 *   <tr><td>Lambda</td><td>Invocations/day</td><td>&lt; 1</td><td>Idle</td></tr>
 *   <tr><td>Lambda</td><td>Error rate</td><td>&gt; 50%</td><td>High Error Rate</td></tr>
 *   <tr><td>CodePipeline</td><td>Days since execution</td><td>&gt; 90 days</td><td>Idle</td></tr>
 * </table>
 *
 * @see ResourceAnalyzer#isActionable(com.cloudsentinel.dto.ResourceDto)
 */
@Service
public class RecommendationEngine {

    // ── Threshold constants ──────────────────────────────────────────
    /** CPU utilization below this percentage is considered idle. */
    public static final double IDLE_CPU_PERCENT = 5.0;
    /** CPU utilization below this percentage is low utilization. */
    public static final double LOW_CPU_PERCENT = 20.0;
    /** CPU utilization below this percentage is moderate utilization. */
    public static final double MODERATE_CPU_PERCENT = 50.0;
    /** Days of inactivity before an IAM user/role or secret is considered unused. */
    public static final int INACTIVITY_DAYS = 90;
    /** Days since last modification before an SSM parameter is considered stale. */
    public static final int STALE_DAYS = 180;
    /** Days since last pipeline execution before it's considered idle. */
    public static final int PIPELINE_IDLE_DAYS = 90;
    /** Days since last pipeline execution before it's considered low activity. */
    public static final int PIPELINE_LOW_DAYS = 30;
    /** Days since last Glue job run before it's considered idle. */
    public static final int GLUE_IDLE_DAYS = 90;
    /** Days since last Glue crawler run before it's considered idle. */
    public static final int CRAWLER_IDLE_DAYS = 90;
    /** Snapshot age in days before flagged as old. */
    public static final int SNAPSHOT_OLD_DAYS = 90;
    /** NAT Gateway bytes out below this is idle (1 KB). */
    public static final long NAT_IDLE_BYTES = 1024;
    /** NAT Gateway bytes out below this is low utilization (1 MB). */
    public static final long NAT_LOW_BYTES = 1_048_576;
    /** Lambda error rate above this fraction triggers high-error-rate flag. */
    public static final double LAMBDA_ERROR_THRESHOLD = 0.5;

    /**
     * Generates a CPU-based recommendation for compute resources (EC2, RDS, Aurora, Redshift).
     *
     * <p>Threshold ladder:</p>
     * <ul>
     *   <li>Stopped/stopping state &rarr; "Consider Terminating - Stopped"</li>
     *   <li>CPU &lt; 5% &rarr; "Idle - Consider Downsizing or Terminating"</li>
     *   <li>CPU &lt; 20% &rarr; "Low Utilization - Consider Downsizing"</li>
     *   <li>CPU &lt; 50% &rarr; "Moderate Utilization"</li>
     *   <li>CPU &ge; 50% &rarr; "Active - Good Utilization"</li>
     * </ul>
     *
     * @param cpu   average CPU utilization percentage (0-100)
     * @param state the resource's operational state (e.g., "running", "stopped", "stopping")
     * @return the recommendation string
     */
    public String getRecommendation(double cpu, String state) {
        if ("stopped".equalsIgnoreCase(state) || "stopping".equalsIgnoreCase(state)) {
            return "Consider Terminating - Stopped";
        }
        if (cpu < IDLE_CPU_PERCENT) {
            return "Idle - Consider Downsizing or Terminating";
        }
        if (cpu < LOW_CPU_PERCENT) {
            return "Low Utilization - Consider Downsizing";
        }
        if (cpu < MODERATE_CPU_PERCENT) {
            return "Moderate Utilization";
        }
        return "Active - Good Utilization";
    }

    /**
     * Generates a recommendation for an EBS volume based on its attachment state.
     *
     * <p>Volumes in the "available" state are unattached to any EC2 instance and are
     * candidates for deletion. Attached volumes are marked as "In Use".</p>
     *
     * @param state the EBS volume state (e.g., "available", "in-use")
     * @return the recommendation string
     */
    public String getEbsRecommendation(String state) {
        if ("available".equalsIgnoreCase(state)) {
            return "Delete - Unattached";
        }
        return "In Use";
    }

    /**
     * Generates a default recommendation for Elastic Load Balancers.
     *
     * <p>Load balancers always return "Review Usage" as their baseline recommendation.
     * The correlation engine may later upgrade this to "Review - No Healthy Targets" if
     * the target count is zero.</p>
     *
     * @return the recommendation string "Review Usage"
     */
    public String getElbRecommendation() {
        return "Review Usage";
    }

    /**
     * Generates a recommendation for an Elastic IP address.
     *
     * <p>Unassociated EIPs incur charges (~$3.65/mo) and should be released if not needed.</p>
     *
     * @param associated {@code true} if the EIP is associated with an instance or ENI
     * @return "In Use" if associated, "Release - Unattached" otherwise
     */
    public String getElasticIpRecommendation(boolean associated) {
        return associated ? "In Use" : "Release - Unattached";
    }

    /**
     * Generates a recommendation for a NAT Gateway based on outbound traffic volume.
     *
     * <p>Thresholds:</p>
     * <ul>
     *   <li>&lt; 1 KB &rarr; Idle (effectively zero traffic)</li>
     *   <li>&lt; 1 MB &rarr; Low Utilization</li>
     *   <li>&ge; 1 MB &rarr; Active</li>
     * </ul>
     *
     * @param bytesOut total outbound bytes over the measurement period
     * @return the recommendation string
     */
    public String getNatGatewayRecommendation(double bytesOut) {
        if (bytesOut < NAT_IDLE_BYTES) {
            return "Idle - Low Traffic";
        }
        if (bytesOut < NAT_LOW_BYTES) {
            return "Low Utilization - Consider Downsizing";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an S3 bucket based on object count and access recency.
     *
     * <p>Classification:</p>
     * <ul>
     *   <li>0 objects &rarr; Empty - Consider Deleting</li>
     *   <li>No recent access &rarr; Inactive - No Recent Access</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param objects      total number of objects in the bucket
     * @param recentAccess {@code true} if the bucket has been accessed recently
     * @return the recommendation string
     */
    public String getS3Recommendation(long objects, boolean recentAccess) {
        if (objects == 0) {
            return "Empty - Consider Deleting";
        }
        if (!recentAccess) {
            return "Inactive - No Recent Access";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a DynamoDB table based on read and write capacity usage.
     *
     * <p>Thresholds:</p>
     * <ul>
     *   <li>Read &lt; 1 AND Write &lt; 1 &rarr; Idle</li>
     *   <li>Read &lt; 10 AND Write &lt; 10 &rarr; Low Utilization</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param read  consumed read capacity units
     * @param write consumed write capacity units
     * @return the recommendation string
     */
    public String getDynamoDbRecommendation(double read, double write) {
        if (read < 1 && write < 1) {
            return "Idle - No Read/Write Activity";
        }
        if (read < 10 && write < 10) {
            return "Low Utilization - Consider Downsizing";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an SQS queue based on daily message volume.
     *
     * <p>Thresholds:</p>
     * <ul>
     *   <li>&lt; 1 message/day &rarr; Idle</li>
     *   <li>&lt; 10 messages/day &rarr; Low Utilization</li>
     *   <li>&ge; 10 messages/day &rarr; Active</li>
     * </ul>
     *
     * @param messagesSentPerDay average number of messages sent per day
     * @return the recommendation string
     */
    public String getSqsRecommendation(double messagesSentPerDay) {
        if (messagesSentPerDay < 1) {
            return "Idle - No Messages Sent";
        }
        if (messagesSentPerDay < 10) {
            return "Low Utilization";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an SNS topic based on subscriber count and publish activity.
     *
     * <p>Classification:</p>
     * <ul>
     *   <li>0 subscriptions AND &lt; 1 publish/day &rarr; Idle</li>
     *   <li>&lt; 1 publish/day (with subscriptions) &rarr; Low Utilization</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param subscriptionCount number of active subscriptions
     * @param publishesPerDay   average number of publishes per day
     * @return the recommendation string
     */
    public String getSnsRecommendation(int subscriptionCount, double publishesPerDay) {
        if (subscriptionCount == 0 && publishesPerDay < 1) {
            return "Idle - No Subscriptions or Publishes";
        }
        if (publishesPerDay < 1) {
            return "Low Utilization - No Recent Publishes";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a Lambda function based on invocation and error rates.
     *
     * <p>Classification:</p>
     * <ul>
     *   <li>&lt; 1 invocation/day &rarr; Idle</li>
     *   <li>Error rate &gt; 50% of invocations &rarr; High Error Rate - Review</li>
     *   <li>&lt; 10 invocations/day &rarr; Low Utilization</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param invocationsPerDay average daily invocation count
     * @param errorsPerDay      average daily error count
     * @return the recommendation string
     */
    public String getLambdaRecommendation(double invocationsPerDay, double errorsPerDay) {
        if (invocationsPerDay < 1) {
            return "Idle - No Invocations";
        }
        if (errorsPerDay > invocationsPerDay * LAMBDA_ERROR_THRESHOLD) {
            return "High Error Rate - Review";
        }
        if (invocationsPerDay < 10) {
            return "Low Utilization";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a VPC based on the number of running instances.
     *
     * @param instanceCount number of running EC2 instances in the VPC
     * @return "Unused - No Running Instances" if empty, "In Use" otherwise
     */
    public String getVpcRecommendation(int instanceCount) {
        if (instanceCount == 0) {
            return "Unused - No Running Instances";
        }
        return "In Use";
    }

    /**
     * Generates a recommendation for an EKS cluster based on status and node groups.
     *
     * @param status         the cluster status (e.g., "ACTIVE", "CREATING")
     * @param nodeGroupCount number of managed node groups
     * @return the recommendation string
     */
    public String getEksRecommendation(String status, int nodeGroupCount) {
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            return "Review - Cluster " + status;
        }
        if (nodeGroupCount == 0) {
            return "Idle - No Node Groups";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an ECS cluster based on running tasks and services.
     *
     * @param runningTasks number of currently running tasks
     * @param serviceCount number of registered services
     * @return the recommendation string
     */
    public String getEcsRecommendation(int runningTasks, int serviceCount) {
        if (runningTasks == 0 && serviceCount == 0) {
            return "Idle - No Services or Tasks";
        }
        if (runningTasks == 0) {
            return "Idle - No Running Tasks";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an Aurora cluster. Delegates to the CPU-based
     * {@link #getRecommendation(double, String)} method.
     *
     * @param cpu    average CPU utilization percentage
     * @param status the cluster status
     * @return the recommendation string
     */
    public String getAuroraRecommendation(double cpu, String status) {
        return getRecommendation(cpu, status);
    }

    /**
     * Generates a recommendation for a Redshift cluster based on CPU and status.
     *
     * <p>Non-available clusters get a "Review" recommendation. Available clusters are
     * evaluated using the standard CPU threshold ladder.</p>
     *
     * @param cpu    average CPU utilization percentage
     * @param status the cluster status (e.g., "available", "paused")
     * @return the recommendation string
     */
    public String getRedshiftRecommendation(double cpu, String status) {
        if (!"available".equalsIgnoreCase(status)) {
            return "Review - Cluster " + status;
        }
        return getRecommendation(cpu, "running");
    }

    /**
     * Generates a recommendation for an IAM user based on MFA status and login recency.
     *
     * <p>Classification:</p>
     * <ul>
     *   <li>Last login &gt; 90 days ago &rarr; Unused (actionable)</li>
     *   <li>MFA not enabled &rarr; Review (security finding, not actionable for cost)</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param mfaEnabled         {@code true} if the user has MFA configured
     * @param daysSinceLastLogin number of days since the user's last console login
     * @return the recommendation string
     */
    public String getIamUserRecommendation(boolean mfaEnabled, int daysSinceLastLogin) {
        if (daysSinceLastLogin > INACTIVITY_DAYS) {
            return "Unused - Inactive > 90 Days";
        }
        if (!mfaEnabled) {
            return "Enable MFA - User Lacks Multi-Factor Authentication";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an IAM role based on usage recency.
     *
     * <p>Roles not used in over 90 days are classified as "Unused" (actionable).</p>
     *
     * @param daysSinceLastUsed number of days since the role was last assumed
     * @return the recommendation string
     */
    public String getIamRoleRecommendation(int daysSinceLastUsed) {
        if (daysSinceLastUsed > INACTIVITY_DAYS) {
            return "Unused - Not Used > 90 Days";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a KMS key based on its state and rotation configuration.
     *
     * @param rotationEnabled {@code true} if automatic key rotation is enabled
     * @param keyState        the key state (e.g., "Enabled", "Disabled", "PendingDeletion")
     * @return the recommendation string
     */
    public String getKmsRecommendation(boolean rotationEnabled, String keyState) {
        if ("PendingDeletion".equalsIgnoreCase(keyState)) {
            return "Stale - Key Pending Deletion";
        }
        if (!"Enabled".equalsIgnoreCase(keyState)) {
            return "Stale - Key " + keyState;
        }
        if (!rotationEnabled) {
            return "Rotate Key - Auto-Rotation Disabled";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a CloudWatch alarm based on its state.
     *
     * <p>Alarms in "INSUFFICIENT_DATA" state may be monitoring deleted resources.</p>
     *
     * @param stateValue the alarm state (e.g., "OK", "ALARM", "INSUFFICIENT_DATA")
     * @return the recommendation string
     */
    public String getCloudWatchAlarmRecommendation(String stateValue) {
        if ("INSUFFICIENT_DATA".equalsIgnoreCase(stateValue)) {
            return "Stale - Alarm Has Insufficient Data";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a CloudWatch Log Group based on its retention policy.
     *
     * <p>Log groups without a retention policy retain data indefinitely, leading to
     * unbounded storage growth and cost.</p>
     *
     * @param retentionDays the configured retention period in days, or {@code null} if no policy is set
     * @return the recommendation string
     */
    public String getCloudWatchLogGroupRecommendation(Integer retentionDays) {
        if (retentionDays == null) {
            return "Missing Retention Policy - Logs Never Expire";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a CloudTrail trail based on its logging status.
     *
     * @param isLogging {@code true} if the trail is actively recording API calls
     * @return the recommendation string
     */
    public String getCloudTrailRecommendation(boolean isLogging) {
        if (!isLogging) {
            return "Enable Logging - CloudTrail Not Recording";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a Secrets Manager secret.
     *
     * <p>Classification priority:</p>
     * <ol>
     *   <li>Plain-text secret (not encrypted) &rarr; Review - should use Parameter Store</li>
     *   <li>Not accessed in &gt; 90 days &rarr; Unused (actionable)</li>
     *   <li>Rotation disabled &rarr; Review (security finding)</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ol>
     *
     * @param daysSinceAccessed number of days since the secret was last retrieved
     * @param rotationEnabled   {@code true} if automatic rotation is configured
     * @param isPlainText       {@code true} if the secret value is not a sensitive/encrypted type
     * @return the recommendation string
     */
    public String getSecretRecommendation(int daysSinceAccessed, boolean rotationEnabled, boolean isPlainText) {
        if (isPlainText) {
            return "Misconfigured - Non-Sensitive Value in Secrets Manager";
        }
        if (daysSinceAccessed > INACTIVITY_DAYS) {
            return "Unused - Not Accessed > 90 Days";
        }
        if (!rotationEnabled) {
            return "Rotate Secret - Auto-Rotation Disabled";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for an SSM Parameter Store parameter.
     *
     * <p>Classification:</p>
     * <ul>
     *   <li>Contains a sensitive value stored as plain String type &rarr; Review (should use Secrets Manager)</li>
     *   <li>Not modified in &gt; 180 days &rarr; Review</li>
     *   <li>Otherwise &rarr; Active</li>
     * </ul>
     *
     * @param daysSinceModified number of days since the parameter was last modified
     * @param type              the parameter type (e.g., "String", "SecureString", "StringList")
     * @param hasSecretValue    {@code true} if the parameter value appears to contain sensitive data
     * @return the recommendation string
     */
    public String getParameterRecommendation(int daysSinceModified, String type, boolean hasSecretValue) {
        if (hasSecretValue && "String".equals(type)) {
            return "Exposed - Sensitive Value Stored as Plain String";
        }
        if (daysSinceModified > STALE_DAYS) {
            return "Stale - Not Modified > 180 Days";
        }
        return "Active";
    }

    /**
     * Generates a recommendation for a CloudFormation stack based on its status.
     *
     * <p>Handles rollback, failed, delete-in-progress, and in-progress states.</p>
     *
     * @param status the CloudFormation stack status string (e.g., "CREATE_COMPLETE", "ROLLBACK_COMPLETE")
     * @return the recommendation string
     */
    public String getCloudFormationRecommendation(String status) {
        if (status == null) return "Review";
        if (status.contains("DELETE_COMPLETE") || status.contains("DELETE_IN_PROGRESS")) return "Skipped - Being Deleted";
        if (status.contains("ROLLBACK_COMPLETE") || status.contains("ROLLBACK_FAILED")) return "Stale - Stack in Rollback State";
        if (status.contains("CREATE_FAILED") || status.contains("UPDATE_ROLLBACK_COMPLETE")) return "Stale - Stack in Failed State";
        if (status.contains("DELETE_FAILED")) return "Stale - Stack Delete Failed";
        if (status.contains("IN_PROGRESS")) return "Active - In Progress";
        return "Active";
    }

    /**
     * Generates a recommendation for a DMS replication instance based on status and task presence.
     *
     * @param status  the replication instance status
     * @param hasTasks {@code true} if the instance has active replication tasks
     * @return the recommendation string
     */
    public String getDmsRecommendation(String status, boolean hasTasks) {
        if (status == null) return "Review";
        if (status.contains("stopped")) return "Idle - Stopped";
        if (status.contains("failed")) return "Review - Failed";
        if (status.contains("available") && !hasTasks) return "Review - No Replication Tasks";
        return "Active";
    }

    /**
     * Generates a recommendation for an ACM certificate based on its validation status and usage.
     *
     * <p>Expired and revoked certificates are marked for deletion. Certificates not in use
     * by any AWS resource are flagged for review.</p>
     *
     * @param status the certificate status (e.g., "ISSUED", "EXPIRED", "REVOKED", "PENDING_VALIDATION")
     * @param inUse  {@code true} if the certificate is associated with at least one AWS resource
     * @return the recommendation string
     */
    public String getAcmRecommendation(String status, boolean inUse) {
        if (status == null) return "Review";
        if ("EXPIRED".equals(status)) return "Expired - Certificate Needs Renewal";
        if ("REVOKED".equals(status)) return "Delete - Revoked Certificate";
        if ("FAILED".equals(status)) return "Misconfigured - Validation Failed";
        if ("PENDING_VALIDATION".equals(status)) return "Missing Validation - Certificate Pending";
        if ("INACTIVE".equals(status)) return "Stale - Certificate Inactive";
        if (!inUse) return "Unused - Certificate Not Attached";
        return "Active";
    }

    /**
     * Generates a recommendation for a WAF Web ACL based on associations and rules.
     *
     * @param hasAssociations {@code true} if the Web ACL is associated with at least one resource
     * @param ruleCount       number of rules configured in the Web ACL
     * @return the recommendation string
     */
    public String getWafRecommendation(boolean hasAssociations, int ruleCount) {
        if (!hasAssociations) return "Unused - WAF Not Associated with Any Resource";
        if (ruleCount == 0) return "Misconfigured - WAF Has No Rules";
        return "Active";
    }

    /**
     * Generates a recommendation for an Athena workgroup based on its state.
     *
     * @param state the workgroup state (e.g., "ENABLED", "DISABLED")
     * @param name  the workgroup name
     * @return the recommendation string
     */
    public String getAthenaRecommendation(String state, String name) {
        if ("DISABLED".equalsIgnoreCase(state)) return "Idle - Disabled";
        return "Active";
    }

    /**
     * Generates a recommendation for a DocumentDB cluster based on status and member count.
     *
     * @param status      the cluster status (e.g., "available", "stopped")
     * @param memberCount number of instances in the cluster
     * @return the recommendation string
     */
    public String getDocumentDbRecommendation(String status, int memberCount) {
        if ("stopped".equalsIgnoreCase(status)) return "Idle - Stopped";
        if ("available".equalsIgnoreCase(status) && memberCount == 0) return "Review - No Instances";
        if (status != null && status.contains("fail")) return "Review - Failed";
        return "Active";
    }

    /**
     * Generates a recommendation for a Neptune cluster based on status and member count.
     *
     * @param status      the cluster status (e.g., "available", "stopped")
     * @param memberCount number of instances in the cluster
     * @return the recommendation string
     */
    public String getNeptuneRecommendation(String status, int memberCount) {
        if ("stopped".equalsIgnoreCase(status)) return "Idle - Stopped";
        if ("available".equalsIgnoreCase(status) && memberCount == 0) return "Review - No Instances";
        return "Active";
    }

    /**
     * Generates a recommendation for a MemoryDB cluster based on status and shard count.
     *
     * @param status     the cluster status (e.g., "available")
     * @param shardCount number of shards in the cluster
     * @return the recommendation string
     */
    public String getMemoryDbRecommendation(String status, int shardCount) {
        if (!"available".equalsIgnoreCase(status)) return "Review - " + (status != null ? status : "Unknown");
        if (shardCount == 0) return "Review - No Shards";
        return "Active";
    }

    /**
     * Generates a recommendation for a Lightsail instance based on its state.
     *
     * <p>Stopped Lightsail instances still incur charges for attached storage and static IPs.</p>
     *
     * @param state the instance state (e.g., "running", "stopped")
     * @return the recommendation string
     */
    public String getLightsailRecommendation(String state) {
        if ("stopped".equalsIgnoreCase(state)) return "Idle - Stopped";
        if ("running".equalsIgnoreCase(state) || "available".equalsIgnoreCase(state)) return "Active";
        return "Review - " + (state != null ? state : "Unknown");
    }

    /**
     * Generates a recommendation for a CodePipeline based on execution recency.
     *
     * <p>Thresholds:</p>
     * <ul>
     *   <li>&gt; 90 days &rarr; Idle (actionable)</li>
     *   <li>&gt; 30 days &rarr; Low Activity</li>
     *   <li>&le; 30 days &rarr; Active</li>
     * </ul>
     *
     * @param daysSinceLastExecution number of days since the last pipeline execution
     * @return the recommendation string
     */
    public String getCodePipelineRecommendation(long daysSinceLastExecution) {
        if (daysSinceLastExecution > PIPELINE_IDLE_DAYS) return "Idle - No Recent Executions";
        if (daysSinceLastExecution > PIPELINE_LOW_DAYS) return "Low Activity";
        return "Active";
    }

    /**
     * Generates a recommendation for a Managed Grafana workspace based on its status.
     *
     * @param status the workspace status (e.g., "ACTIVE", "CREATING", "FAILED")
     * @return the recommendation string
     */
    public String getGrafanaRecommendation(String status) {
        if ("ACTIVE".equalsIgnoreCase(status)) return "Active";
        if (status != null && (status.contains("CREATING") || status.contains("UPDATING"))) return "Active - In Progress";
        return "Review - " + (status != null ? status : "Unknown");
    }

    // ══════════════════════════════════════════════════════════════════
    // Additional resource-specific methods (wired from scanners)
    // ══════════════════════════════════════════════════════════════════

    public String getApiGatewayRecommendation(double requestsPerDay) {
        if (requestsPerDay == 0) return "Idle - No Requests";
        if (requestsPerDay < 100) return "Low Utilization";
        return "Active";
    }

    public String getCloudFrontRecommendation(boolean enabled) {
        return enabled ? "Active" : "Review - Distribution Disabled";
    }

    public String getEbsSnapshotRecommendation(boolean orphaned, boolean isOld) {
        if (orphaned) return "Review - Orphaned Snapshot";
        if (isOld) return "Review - Old Snapshot (>90 days)";
        return "Active";
    }

    public String getEcrRecommendation(int imageCount, boolean hasStaleImages) {
        if (imageCount == 0) return "Empty - Consider Deleting";
        if (hasStaleImages) return "Review - Stale Images";
        return "Active";
    }

    public String getEfsRecommendation(long sizeInBytes, int mountTargetCount) {
        if (sizeInBytes < 1_048_576L) return "Empty - Consider Deleting";
        if (mountTargetCount == 0) return "Unused - No Mount Targets";
        return "Active";
    }

    public String getElasticBeanstalkRecommendation(String status, String health) {
        if ("Red".equals(health)) return "Review - Unhealthy";
        if ("Ready".equals(status) && "Green".equals(health)) return "Active";
        return "Review - " + (status != null ? status : "Unknown");
    }

    public String getFsxRecommendation(String lifecycle, long storageCapacity) {
        if (!"AVAILABLE".equals(lifecycle)) return "Review - " + (lifecycle != null ? lifecycle : "Unknown");
        if (storageCapacity > 0) return "Active";
        return "Review";
    }

    public String getGlueJobRecommendation(long daysSinceLastRun, boolean hasRuns) {
        if (!hasRuns || daysSinceLastRun > GLUE_IDLE_DAYS) return "Idle - No Recent Runs";
        return "Active";
    }

    public String getGlueCrawlerRecommendation(String state, long daysSinceLastCrawl) {
        if (!"READY".equals(state)) return "Active";
        if (daysSinceLastCrawl <= CRAWLER_IDLE_DAYS) return "Active";
        return "Idle";
    }

    public String getKinesisRecommendation(String status, double incomingRecords) {
        if (!"ACTIVE".equals(status)) return "Review - Stream " + status;
        if (incomingRecords == 0) return "Idle - No Incoming Records";
        return "Active";
    }

    public String getRdsSnapshotRecommendation(boolean isOld) {
        return isOld ? "Review - Old Snapshot (>90 days)" : "Active";
    }

    public String getRoute53Recommendation(long recordCount) {
        if (recordCount <= 2) return "Empty - Consider Deleting";
        return "Active";
    }

    public String getSageMakerEndpointRecommendation(String status) {
        if (!"InService".equals(status)) return "Review - Endpoint " + status;
        return "Active";
    }

    public String getSageMakerNotebookRecommendation(String status) {
        if ("Stopped".equals(status)) return "Stopped - Consider Deleting";
        if ("InService".equals(status)) return "Review - Running Notebook (billed while running)";
        return "Active";
    }

    public String getStepFunctionsRecommendation(double executionsPerDay) {
        if (executionsPerDay == 0) return "Idle - No Executions";
        if (executionsPerDay < 10) return "Low Utilization";
        return "Active";
    }

    public String getTransferFamilyRecommendation(String state) {
        if ("OFFLINE".equals(state)) return "Review - Server Offline";
        if ("ONLINE".equals(state)) return "Active";
        if ("STOP_FAILED".equals(state)) return "Review - Stop Failed";
        return "Review - " + (state != null ? state : "Unknown");
    }
}
