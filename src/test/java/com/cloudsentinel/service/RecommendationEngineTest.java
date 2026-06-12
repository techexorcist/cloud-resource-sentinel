package com.cloudsentinel.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    // --- CPU-based (EC2/RDS/Aurora/Redshift) ---

    @Test
    void getRecommendation_stoppedInstance() {
        assertEquals("Consider Terminating - Stopped", engine.getRecommendation(10, "stopped"));
    }

    @Test
    void getRecommendation_cpuBelow5() {
        assertEquals("Idle - Consider Downsizing or Terminating", engine.getRecommendation(2.5, "running"));
    }

    @Test
    void getRecommendation_cpuBetween5And20() {
        assertEquals("Low Utilization - Consider Downsizing", engine.getRecommendation(15, "running"));
    }

    @Test
    void getRecommendation_cpuBetween20And50() {
        assertEquals("Moderate Utilization", engine.getRecommendation(35, "running"));
    }

    @Test
    void getRecommendation_cpuAbove50() {
        assertEquals("Active - Good Utilization", engine.getRecommendation(75, "running"));
    }

    // --- EBS ---

    @Test
    void getEbsRecommendation_available() {
        assertEquals("Delete - Unattached", engine.getEbsRecommendation("available"));
    }

    @Test
    void getEbsRecommendation_inUse() {
        assertEquals("In Use", engine.getEbsRecommendation("in-use"));
    }

    // --- Elastic IP ---

    @Test
    void getElasticIpRecommendation_associated() {
        assertEquals("In Use", engine.getElasticIpRecommendation(true));
    }

    @Test
    void getElasticIpRecommendation_unassociated() {
        assertEquals("Release - Unattached", engine.getElasticIpRecommendation(false));
    }

    // --- NAT Gateway ---

    @Test
    void getNatGatewayRecommendation_zeroBytesOut() {
        assertEquals("Idle - Low Traffic", engine.getNatGatewayRecommendation(0));
    }

    @Test
    void getNatGatewayRecommendation_lowTraffic() {
        assertEquals("Idle - Low Traffic", engine.getNatGatewayRecommendation(500));
    }

    @Test
    void getNatGatewayRecommendation_active() {
        assertEquals("Active", engine.getNatGatewayRecommendation(2_000_000));
    }

    // --- S3 ---

    @Test
    void getS3Recommendation_emptyBucket() {
        assertEquals("Empty - Consider Deleting", engine.getS3Recommendation(0, true));
    }

    @Test
    void getS3Recommendation_noRecentAccess() {
        assertEquals("Inactive - No Recent Access", engine.getS3Recommendation(100, false));
    }

    @Test
    void getS3Recommendation_active() {
        assertEquals("Active", engine.getS3Recommendation(100, true));
    }

    // --- DynamoDB ---

    @Test
    void getDynamoDbRecommendation_zeroActivity() {
        assertEquals("Idle - No Read/Write Activity", engine.getDynamoDbRecommendation(0, 0));
    }

    @Test
    void getDynamoDbRecommendation_lowActivity() {
        assertEquals("Low Utilization - Consider Downsizing", engine.getDynamoDbRecommendation(5, 3));
    }

    @Test
    void getDynamoDbRecommendation_active() {
        assertEquals("Active", engine.getDynamoDbRecommendation(50, 25));
    }

    // --- IAM (security verbs) ---

    @Test
    void getIamUserRecommendation_noMfa() {
        String rec = engine.getIamUserRecommendation(false, 10);
        assertTrue(rec.startsWith("Enable"), "Should use 'Enable' prefix, got: " + rec);
        assertTrue(rec.contains("MFA"), "Should mention MFA, got: " + rec);
    }

    @Test
    void getIamUserRecommendation_inactive() {
        assertEquals("Unused - Inactive > 90 Days", engine.getIamUserRecommendation(true, 100));
    }

    @Test
    void getIamUserRecommendation_active() {
        assertEquals("Active", engine.getIamUserRecommendation(true, 10));
    }

    // --- KMS (security verbs) ---

    @Test
    void getKmsRecommendation_rotationDisabled() {
        String rec = engine.getKmsRecommendation(false, "Enabled");
        assertTrue(rec.startsWith("Rotate"), "Should use 'Rotate' prefix, got: " + rec);
    }

    @Test
    void getKmsRecommendation_keyDisabled() {
        String rec = engine.getKmsRecommendation(true, "Disabled");
        assertTrue(rec.startsWith("Stale"), "Should use 'Stale' prefix, got: " + rec);
    }

    @Test
    void getKmsRecommendation_active() {
        assertEquals("Active", engine.getKmsRecommendation(true, "Enabled"));
    }

    // --- CloudTrail (security verbs) ---

    @Test
    void getCloudTrailRecommendation_loggingDisabled() {
        String rec = engine.getCloudTrailRecommendation(false);
        assertTrue(rec.startsWith("Enable"), "Should use 'Enable' prefix, got: " + rec);
    }

    @Test
    void getCloudTrailRecommendation_active() {
        assertEquals("Active", engine.getCloudTrailRecommendation(true));
    }

    // --- CloudWatch (governance verbs) ---

    @Test
    void getCloudWatchAlarmRecommendation_insufficientData() {
        String rec = engine.getCloudWatchAlarmRecommendation("INSUFFICIENT_DATA");
        assertTrue(rec.startsWith("Stale"), "Should use 'Stale' prefix, got: " + rec);
    }

    @Test
    void getCloudWatchLogGroupRecommendation_noRetention() {
        String rec = engine.getCloudWatchLogGroupRecommendation(null);
        assertTrue(rec.startsWith("Missing"), "Should use 'Missing' prefix, got: " + rec);
    }

    @Test
    void getCloudWatchLogGroupRecommendation_withRetention() {
        assertEquals("Active", engine.getCloudWatchLogGroupRecommendation(30));
    }

    // --- Secrets Manager (security verbs) ---

    @Test
    void getSecretRecommendation_rotationDisabled() {
        String rec = engine.getSecretRecommendation(10, false, false);
        assertTrue(rec.startsWith("Rotate"), "Should use 'Rotate' prefix, got: " + rec);
    }

    @Test
    void getSecretRecommendation_plainText() {
        String rec = engine.getSecretRecommendation(10, true, true);
        assertTrue(rec.startsWith("Misconfigured"), "Should use 'Misconfigured' prefix, got: " + rec);
    }

    @Test
    void getSecretRecommendation_notAccessed() {
        assertEquals("Unused - Not Accessed > 90 Days", engine.getSecretRecommendation(100, true, false));
    }

    // --- SSM Parameter Store (governance verbs) ---

    @Test
    void getParameterRecommendation_sensitiveAsPlainString() {
        String rec = engine.getParameterRecommendation(10, "String", true);
        assertTrue(rec.startsWith("Exposed"), "Should use 'Exposed' prefix, got: " + rec);
    }

    @Test
    void getParameterRecommendation_stale() {
        String rec = engine.getParameterRecommendation(200, "SecureString", false);
        assertTrue(rec.startsWith("Stale"), "Should use 'Stale' prefix, got: " + rec);
    }

    // --- CloudFormation (governance verbs) ---

    @Test
    void getCloudFormationRecommendation_rollbackState() {
        String rec = engine.getCloudFormationRecommendation("ROLLBACK_COMPLETE");
        assertTrue(rec.startsWith("Stale"), "Should use 'Stale' prefix, got: " + rec);
    }

    @Test
    void getCloudFormationRecommendation_deleteFailed() {
        String rec = engine.getCloudFormationRecommendation("DELETE_FAILED");
        assertTrue(rec.startsWith("Stale"), "Should use 'Stale' prefix, got: " + rec);
    }

    @Test
    void getCloudFormationRecommendation_active() {
        assertEquals("Active", engine.getCloudFormationRecommendation("CREATE_COMPLETE"));
    }

    // --- ACM (security verbs) ---

    @Test
    void getAcmRecommendation_expired() {
        String rec = engine.getAcmRecommendation("EXPIRED", true);
        assertTrue(rec.startsWith("Expired"), "Should use 'Expired' prefix, got: " + rec);
    }

    @Test
    void getAcmRecommendation_notInUse() {
        String rec = engine.getAcmRecommendation("ISSUED", false);
        assertTrue(rec.startsWith("Unused"), "Should use 'Unused' prefix, got: " + rec);
    }

    @Test
    void getAcmRecommendation_validationFailed() {
        String rec = engine.getAcmRecommendation("FAILED", true);
        assertTrue(rec.startsWith("Misconfigured"), "Should use 'Misconfigured' prefix, got: " + rec);
    }

    @Test
    void getAcmRecommendation_pendingValidation() {
        String rec = engine.getAcmRecommendation("PENDING_VALIDATION", true);
        assertTrue(rec.startsWith("Missing"), "Should use 'Missing' prefix, got: " + rec);
    }

    // --- WAF (security verbs) ---

    @Test
    void getWafRecommendation_notAssociated() {
        String rec = engine.getWafRecommendation(false, 3);
        assertTrue(rec.startsWith("Unused"), "Should use 'Unused' prefix, got: " + rec);
    }

    @Test
    void getWafRecommendation_noRules() {
        String rec = engine.getWafRecommendation(true, 0);
        assertTrue(rec.startsWith("Misconfigured"), "Should use 'Misconfigured' prefix, got: " + rec);
    }

    @Test
    void getWafRecommendation_active() {
        assertEquals("Active", engine.getWafRecommendation(true, 5));
    }

    // --- New engine methods (wired from remaining scanners) ---

    @Test
    void getApiGatewayRecommendation_idle() {
        assertEquals("Idle - No Requests", engine.getApiGatewayRecommendation(0));
    }

    @Test
    void getApiGatewayRecommendation_low() {
        assertEquals("Low Utilization", engine.getApiGatewayRecommendation(50));
    }

    @Test
    void getApiGatewayRecommendation_active() {
        assertEquals("Active", engine.getApiGatewayRecommendation(200));
    }

    @Test
    void getCloudFrontRecommendation_disabled() {
        assertEquals("Review - Distribution Disabled", engine.getCloudFrontRecommendation(false));
    }

    @Test
    void getCloudFrontRecommendation_enabled() {
        assertEquals("Active", engine.getCloudFrontRecommendation(true));
    }

    @Test
    void getEbsSnapshotRecommendation_orphaned() {
        assertTrue(engine.getEbsSnapshotRecommendation(true, false).contains("Orphaned"));
    }

    @Test
    void getEbsSnapshotRecommendation_old() {
        assertTrue(engine.getEbsSnapshotRecommendation(false, true).contains("Old"));
    }

    @Test
    void getEcrRecommendation_empty() {
        assertEquals("Empty - Consider Deleting", engine.getEcrRecommendation(0, false));
    }

    @Test
    void getEcrRecommendation_stale() {
        assertEquals("Review - Stale Images", engine.getEcrRecommendation(5, true));
    }

    @Test
    void getEfsRecommendation_empty() {
        assertEquals("Empty - Consider Deleting", engine.getEfsRecommendation(100, 2));
    }

    @Test
    void getEfsRecommendation_noMountTargets() {
        assertEquals("Unused - No Mount Targets", engine.getEfsRecommendation(5_000_000, 0));
    }

    @Test
    void getElasticBeanstalkRecommendation_unhealthy() {
        assertTrue(engine.getElasticBeanstalkRecommendation("Ready", "Red").contains("Unhealthy"));
    }

    @Test
    void getElasticBeanstalkRecommendation_active() {
        assertEquals("Active", engine.getElasticBeanstalkRecommendation("Ready", "Green"));
    }

    @Test
    void getFsxRecommendation_notAvailable() {
        assertTrue(engine.getFsxRecommendation("CREATING", 100).startsWith("Review"));
    }

    @Test
    void getGlueJobRecommendation_idle() {
        assertEquals("Idle - No Recent Runs", engine.getGlueJobRecommendation(100, true));
    }

    @Test
    void getGlueJobRecommendation_noRuns() {
        assertEquals("Idle - No Recent Runs", engine.getGlueJobRecommendation(0, false));
    }

    @Test
    void getGlueCrawlerRecommendation_idle() {
        assertEquals("Idle", engine.getGlueCrawlerRecommendation("READY", 100));
    }

    @Test
    void getKinesisRecommendation_idle() {
        assertEquals("Idle - No Incoming Records", engine.getKinesisRecommendation("ACTIVE", 0));
    }

    @Test
    void getKinesisRecommendation_notActive() {
        assertTrue(engine.getKinesisRecommendation("DELETING", 0).contains("DELETING"));
    }

    @Test
    void getRdsSnapshotRecommendation_old() {
        assertTrue(engine.getRdsSnapshotRecommendation(true).contains("Old"));
    }

    @Test
    void getRoute53Recommendation_empty() {
        assertEquals("Empty - Consider Deleting", engine.getRoute53Recommendation(2));
    }

    @Test
    void getRoute53Recommendation_active() {
        assertEquals("Active", engine.getRoute53Recommendation(10));
    }

    @Test
    void getSageMakerEndpointRecommendation_notInService() {
        assertTrue(engine.getSageMakerEndpointRecommendation("Failed").contains("Failed"));
    }

    @Test
    void getSageMakerNotebookRecommendation_stopped() {
        assertTrue(engine.getSageMakerNotebookRecommendation("Stopped").startsWith("Stopped"));
    }

    @Test
    void getSageMakerNotebookRecommendation_running() {
        assertTrue(engine.getSageMakerNotebookRecommendation("InService").contains("Running"));
    }

    @Test
    void getStepFunctionsRecommendation_idle() {
        assertEquals("Idle - No Executions", engine.getStepFunctionsRecommendation(0));
    }

    @Test
    void getStepFunctionsRecommendation_low() {
        assertEquals("Low Utilization", engine.getStepFunctionsRecommendation(5));
    }

    @Test
    void getTransferFamilyRecommendation_offline() {
        assertTrue(engine.getTransferFamilyRecommendation("OFFLINE").contains("Offline"));
    }

    @Test
    void getTransferFamilyRecommendation_online() {
        assertEquals("Active", engine.getTransferFamilyRecommendation("ONLINE"));
    }
}
