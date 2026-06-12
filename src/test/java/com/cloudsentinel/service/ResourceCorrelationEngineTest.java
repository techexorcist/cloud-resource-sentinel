package com.cloudsentinel.service;

import com.cloudsentinel.dto.ResourceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResourceCorrelationEngineTest {

    private ResourceCorrelationEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ResourceCorrelationEngine();
    }

    @Test
    void ebsVolume_attachedToStoppedEc2_setsRecommendationDetail() {
        ResourceDto ec2 = makeResource("EC2", "i-abc123", "stopped", "In Use");
        ec2.setInstanceType("t3.medium");

        ResourceDto ebs = makeResource("EBS", "vol-xyz", "in-use", "In Use");
        ebs.setInstanceType("i-abc123 / gp3 / 50GB");
        ebs.setMonthlyCostUsd(5.0);

        List<ResourceDto> resources = new ArrayList<>(List.of(ec2, ebs));
        engine.correlate(resources);

        assertEquals("Review - Attached to Stopped Instance", ebs.getRecommendation());
        assertNotNull(ebs.getRecommendationDetail());
        assertTrue(ebs.getRecommendationDetail().contains("i-abc123"));
        assertTrue(ebs.getRecommendationDetail().contains("stopped"));
    }

    @Test
    void elasticIp_withStoppedEc2_setsDetailMentioningInstance() {
        ResourceDto ec2 = makeResource("EC2", "i-stopped1", "stopped", "Consider Terminating");
        ec2.setInstanceType("t3.micro");

        ResourceDto eip = makeResource("Elastic IP", "eipalloc-001", "associated", "In Use");
        eip.setInstanceType("i-stopped1 / eni-xxx");
        eip.setMonthlyCostUsd(3.65); // Scanner now sets cost for all EIPs

        List<ResourceDto> resources = new ArrayList<>(List.of(ec2, eip));
        engine.correlate(resources);

        assertEquals("Release - Instance is Stopped", eip.getRecommendation());
        assertNotNull(eip.getRecommendationDetail());
        assertTrue(eip.getRecommendationDetail().contains("i-stopped1"));
        assertEquals(3.65, eip.getMonthlyCostUsd()); // Cost preserved from scanner
    }

    @Test
    void natGateway_inVpcWithNoRunningInstances_setsDetail() {
        // NAT Gateway is Active, no VPC in same region has "In Use" recommendation
        ResourceDto nat = makeResource("NAT Gateway", "nat-001", "available", "Active");
        nat.setRegion("us-east-1");
        nat.setMonthlyCostUsd(32.0);

        ResourceDto vpc = makeResource("VPC", "vpc-001", "available", "Idle");
        vpc.setRegion("us-east-1");

        List<ResourceDto> resources = new ArrayList<>(List.of(nat, vpc));
        engine.correlate(resources);

        assertEquals("Review - No Running Compute", nat.getRecommendation());
        assertNotNull(nat.getRecommendationDetail());
        assertTrue(nat.getRecommendationDetail().contains("NAT Gateway"));
    }

    @Test
    void lambda_idleWithLogGroup_addsDetailAndUpdatesRecommendation() {
        ResourceDto lambda = makeResource("Lambda", "arn:aws:lambda:us-east-1:123:function:myFunc", "inactive", "Idle");
        lambda.setResourceName("myFunc");

        ResourceDto logGroup = makeResource("CloudWatch Log Group", "/aws/lambda/myFunc", "active", "No Retention Set");
        logGroup.setResourceName("/aws/lambda/myFunc");
        logGroup.setRecommendation("No Retention Set");

        List<ResourceDto> resources = new ArrayList<>(List.of(lambda, logGroup));
        engine.correlate(resources);

        assertNotNull(logGroup.getRecommendationDetail());
        assertTrue(logGroup.getRecommendationDetail().contains("myFunc"));
        assertTrue(logGroup.getRecommendationDetail().contains("idle"));
    }

    @Test
    void lambda_idleWithLogGroupNoRetention_updatesRecommendation() {
        ResourceDto lambda = makeResource("Lambda", "func-arn", "inactive", "Idle");
        lambda.setResourceName("processOrders");

        ResourceDto logGroup = makeResource("CloudWatch Log Group", "log-group-1", "active", "No Retention Set");
        logGroup.setResourceName("/aws/lambda/processOrders");
        // Recommendation contains "No Retention"
        logGroup.setRecommendation("No Retention Set");

        List<ResourceDto> resources = new ArrayList<>(List.of(lambda, logGroup));
        engine.correlate(resources);

        assertEquals("Review - Idle Lambda + No Retention", logGroup.getRecommendation());
    }

    @Test
    void loadBalancer_noHealthyTargets_setsDetail() {
        ResourceDto elb = makeResource("ELB", "arn:aws:elb:us-east-1:123:lb/my-lb", "active", "Active");
        elb.setInstanceType("ALB / Targets: 0 / Healthy: 0");

        // Need at least 2 resources for correlate to run
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(elb, dummy));
        engine.correlate(resources);

        assertEquals("Review - No Healthy Targets", elb.getRecommendation());
        assertNotNull(elb.getRecommendationDetail());
        assertTrue(elb.getRecommendationDetail().toLowerCase().contains("no healthy targets") ||
                   elb.getRecommendationDetail().contains("zero healthy targets"));
    }

    @Test
    void rdsSnapshot_forDeletedDb_mentionsOrphaned() {
        // No active RDS or Aurora instances
        ResourceDto snap = makeResource("RDS Snapshot", "rds-snap-001", "available", "Review");
        snap.setInstanceType("mysql / my-deleted-db / 50GB");

        // Need a second resource for correlate to process
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(snap, dummy));
        engine.correlate(resources);

        assertEquals("Review - Source DB Deleted", snap.getRecommendation());
        assertNotNull(snap.getRecommendationDetail());
        assertTrue(snap.getRecommendationDetail().toLowerCase().contains("source database"));
    }

    @Test
    void ebsSnapshot_withoutRelatedEc2_mentionsOrphaned() {
        ResourceDto snap = makeResource("EBS Snapshot", "snap-001", "completed", "Orphaned - Volume Deleted");
        snap.setRegion("eu-west-1");
        snap.setInstanceType("vol-deleted / 100GB");

        // No EC2 in same region
        ResourceDto ec2Other = makeResource("EC2", "i-other", "running", "Active");
        ec2Other.setRegion("us-east-1");

        List<ResourceDto> resources = new ArrayList<>(List.of(snap, ec2Other));
        engine.correlate(resources);

        assertNotNull(snap.getRecommendationDetail());
        assertTrue(snap.getRecommendationDetail().contains("No EC2 instances"));
        assertTrue(snap.getRecommendationDetail().contains("eu-west-1"));
    }

    @Test
    void noCorrelations_whenResourcesAreHealthyAndActive() {
        ResourceDto ec2 = makeResource("EC2", "i-running1", "running", "Active");
        ec2.setInstanceType("t3.medium");

        ResourceDto ebs = makeResource("EBS", "vol-attached", "in-use", "In Use");
        ebs.setInstanceType("i-running1 / gp3 / 100GB");

        List<ResourceDto> resources = new ArrayList<>(List.of(ec2, ebs));
        engine.correlate(resources);

        // EBS stays "In Use" because EC2 is running not stopped
        assertEquals("In Use", ebs.getRecommendation());
        assertNull(ebs.getRecommendationDetail());
    }

    @Test
    void emptyResourceList_noError() {
        assertDoesNotThrow(() -> engine.correlate(List.of()));
        assertDoesNotThrow(() -> engine.correlate(null));
    }

    @Test
    void singleResource_noError() {
        ResourceDto single = makeResource("EC2", "i-only", "running", "Active");
        List<ResourceDto> resources = new ArrayList<>(List.of(single));
        assertDoesNotThrow(() -> engine.correlate(resources));
    }

    @Test
    void iamUser_enableMfa_correlationFires() {
        // Uses the ACTUAL recommendation string from RecommendationEngine.getIamUserRecommendation(false, 10)
        ResourceDto iam = makeResource("IAM User", "user-nomfa", "active",
                "Enable MFA - User Lacks Multi-Factor Authentication");
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(iam, dummy));
        engine.correlate(resources);

        assertNotNull(iam.getRecommendationDetail(),
                "IAM correlation should fire for 'Enable MFA' recommendation");
        assertTrue(iam.getRecommendationDetail().contains("MFA"),
                "Detail should mention MFA");
    }

    @Test
    void cloudTrail_enableLogging_correlationFires() {
        // Uses the ACTUAL recommendation string from RecommendationEngine.getCloudTrailRecommendation(false)
        ResourceDto trail = makeResource("CloudTrail", "trail-disabled", "active",
                "Enable Logging - CloudTrail Not Recording");
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(trail, dummy));
        engine.correlate(resources);

        assertNotNull(trail.getRecommendationDetail(),
                "CloudTrail correlation should fire for 'Enable Logging' recommendation");
        assertTrue(trail.getRecommendationDetail().contains("logging"),
                "Detail should mention logging");
    }

    @Test
    void elb_reviewUsage_noComputeInRegion_correlationFires() {
        // ELB with default "Review Usage" in a region with no running compute
        ResourceDto elb = makeResource("Application Load Balancer", "app/my-alb/123", "active", "Review Usage");
        elb.setRegion("eu-west-2");
        elb.setInstanceType("application");
        elb.setMonthlyCostUsd(20.0);

        // No compute in eu-west-2 — only in us-east-1
        ResourceDto ec2 = makeResource("EC2", "i-running", "running", "Active");
        ec2.setRegion("us-east-1");

        List<ResourceDto> resources = new ArrayList<>(List.of(elb, ec2));
        engine.correlate(resources);

        assertEquals("Review - No Healthy Targets", elb.getRecommendation(),
                "ELB in region with no compute should be flagged");
        assertNotNull(elb.getRecommendationDetail());
    }

    @Test
    void acm_unused_correlationFires() {
        // Uses the ACTUAL recommendation string from RecommendationEngine.getAcmRecommendation("ISSUED", false)
        ResourceDto cert = makeResource("ACM Certificate", "arn:aws:acm:us-east-1:123:cert/abc", "issued",
                "Unused - Certificate Not Attached");
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(cert, dummy));
        engine.correlate(resources);

        assertNotNull(cert.getRecommendationDetail(),
                "ACM correlation should fire for 'Unused' recommendation");
    }

    @Test
    void waf_unused_correlationFires() {
        // Uses the ACTUAL recommendation string from RecommendationEngine.getWafRecommendation(false, 3)
        ResourceDto waf = makeResource("WAF", "waf-abc123", "active",
                "Unused - WAF Not Associated with Any Resource");
        ResourceDto dummy = makeResource("EC2", "i-dummy", "running", "Active");

        List<ResourceDto> resources = new ArrayList<>(List.of(waf, dummy));
        engine.correlate(resources);

        assertNotNull(waf.getRecommendationDetail(),
                "WAF correlation should fire for 'Unused' recommendation");
    }

    private ResourceDto makeResource(String type, String id, String state, String recommendation) {
        ResourceDto r = new ResourceDto();
        r.setResourceType(type);
        r.setResourceId(id);
        r.setState(state);
        r.setRecommendation(recommendation);
        r.setRegion("us-east-1");
        r.setResourceName(id);
        r.setMonthlyCostUsd(0);
        return r;
    }
}
