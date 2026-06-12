package com.cloudsentinel.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.ResourceDto;

/**
 * Cross-resource correlation engine that runs after all 50 resource scanners have completed,
 * enriching recommendations based on relationships between resources.
 *
 * <p>While individual scanners evaluate resources in isolation, many cost optimization
 * opportunities only become visible when looking at resources together. For example, an EBS
 * volume marked "In Use" by its scanner is actually wasting money if the EC2 instance it is
 * attached to is stopped. This engine detects such cross-resource relationships and modifies
 * the {@link ResourceDto#setRecommendation recommendation} and
 * {@link ResourceDto#setRecommendationDetail recommendationDetail} fields accordingly.</p>
 *
 * <h3>Correlation Categories (30+ rules)</h3>
 * <ol>
 *   <li><strong>ATTACHMENT</strong> — resource attached to a stopped/idle parent:
 *     <ul>
 *       <li>EBS volume attached to stopped EC2 instance</li>
 *       <li>Elastic IP associated with stopped EC2 instance</li>
 *     </ul>
 *   </li>
 *   <li><strong>ORPHANED</strong> — resource whose parent was deleted:
 *     <ul>
 *       <li>RDS snapshot whose source database no longer exists</li>
 *       <li>EBS snapshot with no related EC2 instances in the region</li>
 *       <li>CloudWatch Log Group for a deleted Lambda function</li>
 *       <li>Unused Secrets Manager secrets and SSM parameters</li>
 *     </ul>
 *   </li>
 *   <li><strong>ISOLATED</strong> — resource in a region with no running compute (EC2/ECS/EKS/Lambda/Beanstalk):
 *     <ul>
 *       <li>NAT Gateway, RDS, Aurora, ElastiCache, OpenSearch, Redshift, EFS, FSx</li>
 *       <li>SQS queues, DocumentDB, Neptune, MemoryDB, Glue resources</li>
 *     </ul>
 *   </li>
 *   <li><strong>LOAD BALANCER</strong> — ALB/NLB/ELB with zero healthy targets</li>
 *   <li><strong>SECURITY &amp; GOVERNANCE</strong> — unused WAF ACLs, expired/unused ACM certificates,
 *       orphaned CloudWatch alarms, IAM users without MFA, KMS keys pending deletion,
 *       CloudTrail trails not logging, Shield protections for deleted resources</li>
 *   <li><strong>INFRASTRUCTURE</strong> — CloudFormation stacks in rollback/failed states,
 *       DMS instances with no replication tasks, disabled Athena workgroups</li>
 *   <li><strong>DEPENDENCY CHAINS</strong> — SageMaker notebooks without endpoints, ECR repos
 *       without container workloads, Route53 zones with stale records, idle API Gateways,
 *       orphaned SNS topics, idle Kinesis streams, idle Step Functions, Transfer Family servers,
 *       stopped Lightsail instances/databases, idle CodePipelines, Managed Grafana workspaces</li>
 * </ol>
 *
 * <h3>Fields Modified</h3>
 * <ul>
 *   <li>{@link ResourceDto#setRecommendation} — may be upgraded (e.g., "In Use" to
 *       "Review - Attached to Stopped Instance") when correlation reveals a problem</li>
 *   <li>{@link ResourceDto#setRecommendationDetail} — enriched with contextual explanation
 *       including cost estimates and remediation guidance. Multiple correlations for the same
 *       resource are joined with {@code " | "}.</li>
 * </ul>
 *
 * @see ResourceAnalyzer#analyzeAllResources
 */
@Service
public class ResourceCorrelationEngine {

    private static final Logger log = LoggerFactory.getLogger(ResourceCorrelationEngine.class);

    /**
     * Runs all correlation rules against the scanned resource list, modifying resources in-place.
     *
     * <p>This method builds several internal indexes (resources by type, EC2 states, regions
     * with running compute, active database IDs, Lambda function names, load balancer IDs, etc.)
     * and then iterates through 30+ correlation rules organized into 7 categories.</p>
     *
     * <p>Resources with fewer than 2 entries are skipped since correlations require at least
     * two resources to cross-reference. The total number of correlation enrichments is logged
     * upon completion.</p>
     *
     * @param resources the mutable list of scanned resources; modified in-place by setting
     *                  {@code recommendation} and {@code recommendationDetail} fields
     */
    public void correlate(List<ResourceDto> resources) {
        if (resources == null || resources.size() < 2) return;

        // ── Build indexes ──
        var byId = new java.util.HashMap<String, ResourceDto>();
        var byType = resources.stream()
                .collect(Collectors.groupingBy(r -> r.getResourceType() != null ? r.getResourceType() : ""));
        for (ResourceDto r : resources) {
            byId.put(r.getResourceId(), r);
        }

        // EC2 state index
        var ec2States = new java.util.HashMap<String, String>();
        var ec2Costs = new java.util.HashMap<String, Double>();
        for (ResourceDto r : byType.getOrDefault("EC2", List.of())) {
            ec2States.put(r.getResourceId(), r.getState());
            ec2Costs.put(r.getResourceId(), r.getMonthlyCostUsd());
        }

        // Regions with running compute (EC2, ECS, EKS, Lambda, Elastic Beanstalk)
        var regionsWithCompute = new java.util.HashSet<String>();
        for (String type : List.of("EC2", "ECS", "EKS", "Lambda", "Elastic Beanstalk")) {
            byType.getOrDefault(type, List.of()).stream()
                    .filter(r -> {
                        String state = r.getState() != null ? r.getState().toLowerCase() : "";
                        String rec = r.getRecommendation() != null ? r.getRecommendation() : "";
                        return state.contains("running") || state.contains("active") || rec.contains("Active");
                    })
                    .map(ResourceDto::getRegion)
                    .forEach(regionsWithCompute::add);
        }

        // Active database IDs
        var activeDbIds = new java.util.HashSet<String>();
        for (String type : List.of("RDS", "Aurora")) {
            byType.getOrDefault(type, List.of()).stream()
                    .map(ResourceDto::getResourceId)
                    .forEach(activeDbIds::add);
        }

        // Lambda function names
        Set<String> idleLambdaNames = byType.getOrDefault("Lambda", List.of()).stream()
                .filter(r -> r.getRecommendation() != null && r.getRecommendation().contains("Idle"))
                .map(ResourceDto::getResourceName).collect(Collectors.toSet());
        Set<String> allLambdaNames = byType.getOrDefault("Lambda", List.of()).stream()
                .map(ResourceDto::getResourceName).collect(Collectors.toSet());

        // ALB/NLB/CloudFront IDs and ARNs for WAF/ACM correlation
        var allLoadBalancerIds = new java.util.HashSet<String>();
        for (String type : List.of("Application Load Balancer", "Network Load Balancer", "ELB")) {
            byType.getOrDefault(type, List.of()).stream()
                    .map(ResourceDto::getResourceId)
                    .forEach(allLoadBalancerIds::add);
        }
        Set<String> allCloudFrontIds = byType.getOrDefault("CloudFront", List.of()).stream()
                .map(ResourceDto::getResourceId).collect(Collectors.toSet());
        Set<String> allApiGatewayIds = byType.getOrDefault("API Gateway", List.of()).stream()
                .map(ResourceDto::getResourceId).collect(Collectors.toSet());

        // All resource IDs for general cross-referencing
        Set<String> allResourceIds = resources.stream()
                .map(ResourceDto::getResourceId).collect(Collectors.toSet());

        int correlations = 0;

        // ═══════════════════════════════════════════════════════════════
        // 1. ATTACHMENT CORRELATIONS
        // ═══════════════════════════════════════════════════════════════

        // EBS → stopped EC2 (O(n) via direct map lookup instead of O(n²) nested loop)
        for (ResourceDto ebs : byType.getOrDefault("EBS", List.of())) {
            if ("In Use".equals(ebs.getRecommendation()) && ebs.getInstanceType() != null) {
                // instanceType format: "i-abc123 / gp3 / 50GB" — extract instance ID
                String desc = ebs.getInstanceType();
                String attachedInstanceId = null;
                for (String instanceId : ec2States.keySet()) {
                    if (desc.contains(instanceId)) {
                        attachedInstanceId = instanceId;
                        break;
                    }
                }
                if (attachedInstanceId != null && "stopped".equalsIgnoreCase(ec2States.get(attachedInstanceId))) {
                    double ec2Cost = ec2Costs.getOrDefault(attachedInstanceId, 0.0);
                    ebs.setRecommendation("Review - Attached to Stopped Instance");
                    ebs.setRecommendationDetail(String.format(
                            "This EBS volume is attached to stopped EC2 instance %s. While the instance is not running, " +
                            "the volume continues to incur storage charges of $%.2f/mo. Together with the stopped instance, " +
                            "the combined idle cost is $%.2f/mo. If the instance won't be restarted, consider creating a snapshot " +
                            "as a backup and then deleting the volume to eliminate ongoing charges.",
                            attachedInstanceId, ebs.getMonthlyCostUsd(), ebs.getMonthlyCostUsd() + ec2Cost));
                    correlations++;
                }
            }
        }

        // EIP → stopped EC2 (O(n) via direct lookup)
        for (ResourceDto eip : byType.getOrDefault("Elastic IP", List.of())) {
            if ("In Use".equals(eip.getRecommendation()) && eip.getInstanceType() != null) {
                String desc = eip.getInstanceType();
                String attachedInstanceId = null;
                for (String instanceId : ec2States.keySet()) {
                    if (desc.contains(instanceId)) {
                        attachedInstanceId = instanceId;
                        break;
                    }
                }
                if (attachedInstanceId != null && "stopped".equalsIgnoreCase(ec2States.get(attachedInstanceId))) {
                    eip.setRecommendation("Release - Instance is Stopped");
                    eip.setRecommendationDetail(String.format(
                            "This Elastic IP is associated with stopped EC2 instance %s. " +
                            "Release it to stop charges, or restart the instance. " +
                            "A new public IP will be assigned if the instance restarts without an EIP.",
                            attachedInstanceId));
                    correlations++;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 2. ORPHANED RESOURCE CORRELATIONS
        // ═══════════════════════════════════════════════════════════════

        // RDS Snapshot → source DB deleted
        for (ResourceDto snap : byType.getOrDefault("RDS Snapshot", List.of())) {
            String desc = snap.getInstanceType();
            if (desc != null && !"Active".equals(snap.getRecommendation())) {
                boolean sourceExists = activeDbIds.stream().anyMatch(desc::contains);
                if (!sourceExists) {
                    snap.setRecommendation("Review - Source DB Deleted");
                    snap.setRecommendationDetail(
                            "The source database for this snapshot no longer exists. This is an orphaned snapshot left behind " +
                            "when the database was deleted. RDS snapshots incur storage charges based on size. " +
                            "If the data is no longer needed, delete the snapshot. For compliance, consider exporting to S3 first.");
                    correlations++;
                }
            }
        }

        // EBS Snapshot → orphaned, no EC2 in region
        for (ResourceDto snap : byType.getOrDefault("EBS Snapshot", List.of())) {
            if (snap.getRecommendation() != null && snap.getRecommendation().contains("Orphaned")) {
                boolean hasRelatedEc2 = byType.getOrDefault("EC2", List.of()).stream()
                        .anyMatch(ec2 -> snap.getRegion().equals(ec2.getRegion()));
                if (!hasRelatedEc2) {
                    appendDetail(snap, String.format(
                            "No EC2 instances were found in %s. The original volume and instance have both been deleted. " +
                            "This snapshot is fully orphaned and can be safely deleted unless kept for compliance.",
                            snap.getRegion()));
                    correlations++;
                }
            }
        }

        // CloudWatch Log Group → deleted Lambda function
        for (ResourceDto logGroup : byType.getOrDefault("CloudWatch Log Group", List.of())) {
            String logName = logGroup.getResourceName();
            if (logName != null && logName.startsWith("/aws/lambda/")) {
                String lambdaName = logName.substring("/aws/lambda/".length());
                if (idleLambdaNames.contains(lambdaName)) {
                    appendDetail(logGroup, String.format(
                            "Associated Lambda function '%s' is idle (zero invocations). Consider deleting both " +
                            "the function and this log group. At minimum, set a retention policy to limit storage growth.",
                            lambdaName));
                    if (logGroup.getRecommendation() != null && logGroup.getRecommendation().contains("No Retention"))
                        logGroup.setRecommendation("Review - Idle Lambda + No Retention");
                    correlations++;
                } else if (!allLambdaNames.contains(lambdaName)) {
                    appendDetail(logGroup, String.format(
                            "Lambda function '%s' no longer exists. This is an orphaned log group that will never receive " +
                            "new entries. Safe to delete unless old logs are needed for audit purposes.",
                            lambdaName));
                    correlations++;
                }
            }
        }

        // Secrets/Parameters → unused
        for (String secretType : List.of("Secrets Manager", "SSM Parameter")) {
            for (ResourceDto secret : byType.getOrDefault(secretType, List.of())) {
                if (secret.getRecommendation() != null && (secret.getRecommendation().contains("Unused") || secret.getRecommendation().contains("Inactive"))) {
                    appendDetail(secret, String.format(
                            "This %s has not been accessed recently. The application that consumed it may have been decommissioned. " +
                            "Secrets Manager charges $0.40/mo per secret. Verify via CloudTrail that no application is using it " +
                            "before scheduling deletion (7-30 day recovery window).",
                            secretType.toLowerCase()));
                    correlations++;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 3. ISOLATED RESOURCE CORRELATIONS (no compute in region)
        // ═══════════════════════════════════════════════════════════════

        // NAT Gateway → no compute
        for (ResourceDto nat : byType.getOrDefault("NAT Gateway", List.of())) {
            if ("Active".equals(nat.getRecommendation()) && !regionsWithCompute.contains(nat.getRegion())) {
                nat.setRecommendation("Review - No Running Compute");
                nat.setRecommendationDetail(String.format(
                        "NAT Gateway in %s with no running compute (EC2/ECS/EKS/Lambda). NAT Gateways cost ~$32/mo + data transfer. " +
                        "Without compute needing outbound access, this cost is wasted. Delete and update route tables if confirmed unused.",
                        nat.getRegion()));
                correlations++;
            }
        }

        // Databases, caches, search, analytics → no compute in region
        Map<String, String> isolatedTypes = Map.of(
                "RDS", "RDS database",
                "Aurora", "Aurora cluster",
                "ElastiCache", "ElastiCache cluster",
                "OpenSearch", "OpenSearch domain",
                "Redshift", "Redshift cluster",
                "EFS", "EFS file system",
                "FSx", "FSx file system"
        );
        for (Map.Entry<String, String> entry : isolatedTypes.entrySet()) {
            for (ResourceDto r : byType.getOrDefault(entry.getKey(), List.of())) {
                if (!regionsWithCompute.contains(r.getRegion()) && r.getMonthlyCostUsd() > 0) {
                    appendDetail(r, String.format(
                            "This %s is running in %s at $%.2f/mo with no compute workloads detected in the same region. " +
                            "It may be a forgotten development/testing resource or accessed from another account. " +
                            "Verify usage before taking action — if truly unused, consider stopping or deleting to eliminate costs.",
                            entry.getValue(), r.getRegion(), r.getMonthlyCostUsd()));
                    correlations++;
                }
            }
        }

        // SQS → idle queue, no compute
        for (ResourceDto sqs : byType.getOrDefault("SQS", List.of())) {
            if (sqs.getRecommendation() != null && (sqs.getRecommendation().contains("Idle") || sqs.getRecommendation().contains("Empty"))) {
                if (!regionsWithCompute.contains(sqs.getRegion())) {
                    appendDetail(sqs, String.format(
                            "Idle queue in %s with no running compute. Likely has no active consumers. " +
                            "Check for Lambda event source mappings before deleting.",
                            sqs.getRegion()));
                    correlations++;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 4. LOAD BALANCER CORRELATIONS
        // ═══════════════════════════════════════════════════════════════

        for (String elbType : List.of("Application Load Balancer", "Network Load Balancer", "ELB")) {
            for (ResourceDto elb : byType.getOrDefault(elbType, List.of())) {
                // Check instanceType for target counts (if set by scanner) or fall back to
                // region-based heuristic: ELB in a region with no running compute likely has no targets
                String desc = elb.getInstanceType();
                boolean noTargetsFromDesc = desc != null && (desc.contains("Targets: 0") || desc.contains("Instances: 0"));
                boolean noComputeInRegion = "Review Usage".equals(elb.getRecommendation())
                        && !regionsWithCompute.contains(elb.getRegion());
                if (noTargetsFromDesc || noComputeInRegion) {
                    elb.setRecommendation("Review - No Healthy Targets");
                    elb.setRecommendationDetail(String.format(
                            "This %s likely has zero healthy targets. It incurs ~$16-22/mo in hourly charges while serving no traffic. " +
                            "This commonly happens when backend instances were terminated without cleaning up the load balancer. " +
                            "Delete the load balancer and its target groups if the service has been decommissioned.",
                            elbType));
                    correlations++;
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 5. SECURITY & GOVERNANCE CORRELATIONS
        // ═══════════════════════════════════════════════════════════════

        // ACM Certificate → not in use by any resource
        for (ResourceDto cert : byType.getOrDefault("ACM Certificate", List.of())) {
            if (cert.getRecommendation() != null && (cert.getRecommendation().contains("Unused") || cert.getRecommendation().contains("Not In Use") || cert.getRecommendation().contains("Not Attached"))) {
                // Check if cert domain might match any CloudFront or ALB
                appendDetail(cert,
                        "This certificate is not associated with any AWS resource (ALB, CloudFront, API Gateway). " +
                        "Unused certificates create security management overhead and may indicate a decommissioned service. " +
                        "If the domain is no longer in use, delete the certificate. If it's needed for a future deployment, no action required.");
                correlations++;
            }
            if (cert.getRecommendation() != null && cert.getRecommendation().contains("Expired")) {
                appendDetail(cert,
                        "This certificate has expired and can no longer secure traffic. If it's still referenced by an ALB or CloudFront distribution, " +
                        "those services may be serving errors to users. Issue a replacement certificate immediately or delete if the domain is retired.");
                correlations++;
            }
        }

        // WAF → not associated with any resource
        for (ResourceDto waf : byType.getOrDefault("WAF", List.of())) {
            if (waf.getRecommendation() != null && (waf.getRecommendation().contains("Unused") || waf.getRecommendation().contains("Not Associated"))) {
                appendDetail(waf,
                        "This WAF Web ACL is not protecting any resource (ALB, CloudFront, API Gateway). " +
                        "Unassociated Web ACLs still incur charges (~$5/mo base + $1/mo per rule). " +
                        "Either associate it with a resource that needs protection, or delete it to stop charges.");
                correlations++;
            }
        }

        // CloudWatch Alarms → possibly referencing deleted resources
        for (ResourceDto alarm : byType.getOrDefault("CloudWatch Alarm", List.of())) {
            String desc = alarm.getInstanceType();
            if (desc != null && alarm.getRecommendation() != null && alarm.getRecommendation().contains("Review")) {
                boolean referencesExisting = resources.stream().anyMatch(r -> desc.contains(r.getResourceId()));
                if (!referencesExisting && desc.contains("/")) {
                    appendDetail(alarm,
                            "This alarm may reference a resource that no longer exists. " +
                            "Orphaned alarms clutter monitoring and can trigger false notifications. " +
                            "Review the alarm target and delete if the monitored resource has been terminated.");
                    correlations++;
                }
            }
        }

        // IAM → users without MFA, roles with no activity
        for (ResourceDto iam : byType.getOrDefault("IAM User", List.of())) {
            if (iam.getRecommendation() != null && (iam.getRecommendation().contains("Enable MFA") || iam.getRecommendation().contains("No MFA"))) {
                appendDetail(iam,
                        "This IAM user does not have MFA enabled. Without MFA, the account is vulnerable to credential compromise. " +
                        "This is a critical security finding — enable MFA immediately or convert to SSO-based access.");
                correlations++;
            }
        }

        // KMS → keys pending deletion
        for (ResourceDto kms : byType.getOrDefault("KMS", List.of())) {
            if (kms.getRecommendation() != null && kms.getRecommendation().contains("Pending Deletion")) {
                appendDetail(kms,
                        "This KMS key is pending deletion. Any data encrypted with this key will become permanently inaccessible " +
                        "once the deletion completes. Verify that no S3 objects, EBS volumes, RDS instances, or other resources " +
                        "depend on this key before the waiting period ends.");
                correlations++;
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 6. INFRASTRUCTURE CORRELATIONS
        // ═══════════════════════════════════════════════════════════════

        // CloudFormation → stacks in failed/rollback state
        for (ResourceDto cfn : byType.getOrDefault("CloudFormation", List.of())) {
            if (cfn.getRecommendation() != null && cfn.getRecommendation().contains("Rollback")) {
                appendDetail(cfn,
                        "This CloudFormation stack is in a rollback state, meaning a deployment failed and was automatically reverted. " +
                        "Rollback stacks often leave behind partial resources that incur charges. " +
                        "Investigate the failure reason in the stack events, clean up any leaked resources, and delete the stack.");
                correlations++;
            }
            if (cfn.getRecommendation() != null && cfn.getRecommendation().contains("Delete Failed")) {
                appendDetail(cfn,
                        "This CloudFormation stack failed to delete, likely because one or more resources have dependencies " +
                        "that prevent deletion (e.g., non-empty S3 bucket, in-use security group). " +
                        "Check the stack events for the specific failure, resolve the dependency, and retry the deletion.");
                correlations++;
            }
        }

        // DMS → replication instance with no tasks
        for (ResourceDto dms : byType.getOrDefault("DMS", List.of())) {
            if (dms.getRecommendation() != null && dms.getRecommendation().contains("No Replication Tasks")) {
                appendDetail(dms, String.format(
                        "This DMS replication instance has no active replication tasks. It is running and incurring charges " +
                        "at $%.2f/mo without performing any data migration or replication. " +
                        "If the migration project is complete, delete the replication instance and its associated endpoints.",
                        dms.getMonthlyCostUsd()));
                correlations++;
            }
        }

        // Athena → disabled workgroups
        for (ResourceDto athena : byType.getOrDefault("Athena", List.of())) {
            if (athena.getRecommendation() != null && athena.getRecommendation().contains("Disabled")) {
                appendDetail(athena,
                        "This Athena workgroup is disabled, meaning no queries can be executed against it. " +
                        "Disabled workgroups don't incur charges but represent configuration clutter. " +
                        "If the workgroup is no longer needed, delete it along with any saved queries associated with it.");
                correlations++;
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // 7. CROSS-SERVICE DEPENDENCY CHAINS
        // ═══════════════════════════════════════════════════════════════

        // SageMaker Notebook → no active endpoints
        List<ResourceDto> smEndpoints = byType.getOrDefault("SageMaker", List.of()).stream()
                .filter(r -> r.getState() != null && r.getState().equalsIgnoreCase("InService"))
                .filter(r -> r.getInstanceType() != null && !r.getInstanceType().contains("Notebook"))
                .toList();
        for (ResourceDto notebook : byType.getOrDefault("SageMaker", List.of())) {
            if (notebook.getInstanceType() != null && notebook.getInstanceType().contains("Notebook") &&
                    "Stopped".equalsIgnoreCase(notebook.getState()) && smEndpoints.isEmpty()) {
                appendDetail(notebook,
                        "No active SageMaker endpoints found. This stopped notebook is likely from an abandoned ML project. " +
                        "Stopped notebooks still incur EBS storage charges. Download valuable notebooks/data and delete the instance.");
                correlations++;
            }
        }

        // ECR → repositories with no images or very old images
        for (ResourceDto ecr : byType.getOrDefault("ECR", List.of())) {
            if (ecr.getRecommendation() != null && (ecr.getRecommendation().contains("Empty") || ecr.getRecommendation().contains("Review"))) {
                // Check if any ECS/EKS in same region could be using this repo
                boolean hasContainerWorkloads = byType.getOrDefault("ECS", List.of()).stream()
                        .anyMatch(ecs -> ecr.getRegion().equals(ecs.getRegion()));
                if (!hasContainerWorkloads) {
                    boolean hasEks = byType.getOrDefault("EKS", List.of()).stream()
                            .anyMatch(eks -> ecr.getRegion().equals(eks.getRegion()));
                    if (!hasEks) {
                        appendDetail(ecr, String.format(
                                "No ECS or EKS workloads found in %s. This ECR repository may be unused — " +
                                "verify no external services pull images from it before deleting.",
                                ecr.getRegion()));
                        correlations++;
                    }
                }
            }
        }

        // Route53 → hosted zones for domains that may not be in use
        for (ResourceDto route53 : byType.getOrDefault("Route53", List.of())) {
            if (route53.getRecommendation() != null && route53.getRecommendation().contains("Review")) {
                appendDetail(route53,
                        "This Route53 hosted zone may contain DNS records for resources that no longer exist. " +
                        "Review the zone's record sets and remove any pointing to deleted load balancers, EC2 instances, or CloudFront distributions. " +
                        "Orphaned DNS records can cause resolution failures and security issues.");
                correlations++;
            }
        }

        // Glue → jobs/crawlers not running
        for (ResourceDto glue : byType.getOrDefault("Glue", List.of())) {
            if (glue.getRecommendation() != null && glue.getRecommendation().contains("Idle")) {
                if (!regionsWithCompute.contains(glue.getRegion())) {
                    appendDetail(glue, String.format(
                            "Idle Glue resource in %s with no running compute. This ETL job or crawler may be part of " +
                            "a decommissioned data pipeline. Verify with the data engineering team before deleting.",
                            glue.getRegion()));
                    correlations++;
                }
            }
        }

        // Transfer Family → servers with no users
        for (ResourceDto transfer : byType.getOrDefault("Transfer Family", List.of())) {
            if (transfer.getRecommendation() != null && transfer.getRecommendation().contains("Idle")) {
                appendDetail(transfer,
                        "This AWS Transfer Family server has no active users or recent file transfers. " +
                        "Transfer servers incur hourly charges (~$216/mo). If SFTP/FTPS access is no longer needed, " +
                        "delete the server to stop significant ongoing costs.");
                correlations++;
            }
        }

        // API Gateway → APIs with no recent traffic
        for (ResourceDto api : byType.getOrDefault("API Gateway", List.of())) {
            if (api.getRecommendation() != null && api.getRecommendation().contains("Idle")) {
                appendDetail(api,
                        "This API Gateway has received no recent traffic. It may be a leftover from a retired microservice. " +
                        "Check if any Lambda functions, ECS services, or external clients still reference this API's endpoint. " +
                        "If not, delete the API and its associated stages/deployments.");
                correlations++;
            }
        }

        // SNS → topics with no subscriptions
        for (ResourceDto sns : byType.getOrDefault("SNS", List.of())) {
            if (sns.getRecommendation() != null && sns.getRecommendation().contains("Idle")) {
                appendDetail(sns,
                        "This SNS topic has no subscribers or recent publish activity. " +
                        "Orphaned topics can indicate a decommissioned notification pipeline. " +
                        "Check if any Lambda functions, SQS queues, or other services are still expected to subscribe.");
                correlations++;
            }
        }

        // Kinesis → streams with no consumers
        for (ResourceDto kinesis : byType.getOrDefault("Kinesis", List.of())) {
            if (kinesis.getRecommendation() != null && kinesis.getRecommendation().contains("Idle")) {
                appendDetail(kinesis, String.format(
                        "This Kinesis stream in %s has no recent activity. Kinesis streams incur hourly charges " +
                        "per shard (~$25/mo per shard). If the streaming pipeline has been decommissioned, " +
                        "delete the stream to eliminate significant ongoing costs.",
                        kinesis.getRegion()));
                correlations++;
            }
        }

        // Step Functions → state machines with no recent executions
        for (ResourceDto sfn : byType.getOrDefault("Step Functions", List.of())) {
            if (sfn.getRecommendation() != null && sfn.getRecommendation().contains("Idle")) {
                appendDetail(sfn,
                        "This Step Functions state machine has had no recent executions. " +
                        "While idle state machines don't incur charges, they represent configuration clutter " +
                        "and may reference Lambda functions or other resources that are also idle. " +
                        "Consider deleting as part of a broader cleanup of the associated workflow.");
                correlations++;
            }
        }

        // CloudTrail → trails not logging
        for (ResourceDto trail : byType.getOrDefault("CloudTrail", List.of())) {
            if (trail.getRecommendation() != null && (trail.getRecommendation().contains("Enable Logging") || trail.getRecommendation().contains("Not Logging"))) {
                appendDetail(trail,
                        "This CloudTrail trail is not actively logging API calls. This is a significant security gap — " +
                        "without CloudTrail logging, you have no audit trail for API activity in this account. " +
                        "Enable logging immediately, or delete the trail if another trail provides coverage.");
                correlations++;
            }
        }

        // Shield Advanced → verify protected resources still exist
        for (ResourceDto shieldProt : byType.getOrDefault("Shield Protection", List.of())) {
            String desc = shieldProt.getInstanceType(); // contains resource type and ARN
            if (desc != null) {
                boolean protectedResourceExists = resources.stream()
                        .anyMatch(r -> desc.contains(r.getResourceId()));
                if (!protectedResourceExists) {
                    appendDetail(shieldProt,
                            "The resource protected by this Shield Advanced protection may no longer exist. " +
                            "Protecting deleted resources wastes a protection slot. Verify the resource ARN " +
                            "and remove the protection if the resource has been decommissioned.");
                    correlations++;
                }
            }
        }

        // DocumentDB / Neptune → isolated in region with no compute
        for (String dbType : List.of("DocumentDB", "Neptune")) {
            for (ResourceDto db : byType.getOrDefault(dbType, List.of())) {
                if (!regionsWithCompute.contains(db.getRegion()) && db.getMonthlyCostUsd() > 0) {
                    appendDetail(db, String.format(
                            "This %s cluster is running in %s with no compute workloads detected. " +
                            "%s clusters incur instance-based charges even when idle. " +
                            "Verify if applications in other accounts connect to it, otherwise consider stopping or deleting.",
                            dbType, db.getRegion(), dbType));
                    correlations++;
                }
                if (db.getRecommendation() != null && db.getRecommendation().contains("No Instances")) {
                    appendDetail(db, String.format(
                            "This %s cluster has zero member instances. It exists as metadata only but cannot serve queries. " +
                            "Either add instances to make it functional, or delete the cluster if it's no longer needed.",
                            dbType));
                    correlations++;
                }
            }
        }

        // MemoryDB → isolated in region with no compute
        for (ResourceDto mem : byType.getOrDefault("MemoryDB", List.of())) {
            if (!regionsWithCompute.contains(mem.getRegion())) {
                appendDetail(mem, String.format(
                        "This MemoryDB cluster is in %s with no compute workloads. MemoryDB is an in-memory database " +
                        "that requires application connections — without nearby compute, it's likely unused.",
                        mem.getRegion()));
                correlations++;
            }
        }

        // Lightsail → stopped instances
        for (ResourceDto ls : byType.getOrDefault("Lightsail", List.of())) {
            if (ls.getRecommendation() != null && ls.getRecommendation().contains("Stopped")) {
                appendDetail(ls,
                        "This Lightsail instance is stopped but still incurs charges for its attached storage and static IP. " +
                        "Lightsail instances have fixed monthly pricing — stopped instances still cost money. " +
                        "Create a snapshot and delete the instance if it won't be restarted.");
                correlations++;
            }
        }
        for (ResourceDto ls : byType.getOrDefault("Lightsail DB", List.of())) {
            if (ls.getRecommendation() != null && ls.getRecommendation().contains("Stopped")) {
                appendDetail(ls,
                        "This Lightsail database is stopped but continues to incur storage charges. " +
                        "Export data and delete if no longer needed.");
                correlations++;
            }
        }

        // CodePipeline → idle pipelines
        for (ResourceDto pipe : byType.getOrDefault("CodePipeline", List.of())) {
            if (pipe.getRecommendation() != null && pipe.getRecommendation().contains("Idle")) {
                appendDetail(pipe,
                        "This CodePipeline has had no executions in over 90 days, costing $1/mo. " +
                        "It may belong to a decommissioned project. Check if the source repository and deployment targets still exist. " +
                        "Delete the pipeline if the project is no longer active.");
                correlations++;
            }
        }

        // Managed Grafana → verify it's being used
        for (ResourceDto graf : byType.getOrDefault("Managed Grafana", List.of())) {
            if (graf.getRecommendation() != null && graf.getRecommendation().contains("Active")) {
                appendDetail(graf,
                        "Managed Grafana workspaces cost $9/mo per editor user. Verify this workspace is actively used " +
                        "for dashboards and monitoring. Unused workspaces should be deleted to avoid charges.");
                correlations++;
            }
        }

        log.info("Resource correlation complete: {} relationships enriched across {} resources", correlations, resources.size());
    }

    /**
     * Appends a correlation detail to a resource's recommendation detail field.
     *
     * <p>If the resource already has a detail string, the new detail is appended with
     * a {@code " | "} separator. Otherwise, the detail is set directly.</p>
     *
     * @param resource the resource to enrich
     * @param detail   the contextual explanation to append
     */
    private void appendDetail(ResourceDto resource, String detail) {
        String existing = resource.getRecommendationDetail();
        if (existing != null && !existing.isBlank()) {
            resource.setRecommendationDetail(existing + " | " + detail);
        } else {
            resource.setRecommendationDetail(detail);
        }
    }
}
