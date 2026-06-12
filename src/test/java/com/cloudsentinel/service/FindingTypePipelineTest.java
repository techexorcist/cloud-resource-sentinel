package com.cloudsentinel.service;

import com.cloudsentinel.dto.FindingType;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.dto.Severity;
import com.cloudsentinel.service.scanner.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the FindingType pipeline is wired end-to-end:
 * scanners declare correct finding types, and isActionable recognizes
 * the recommendation verbs actually emitted by the engine.
 */
class FindingTypePipelineTest {

    // --- Scanner finding types ---

    @Test
    void costScanners_declareCostFindingType() {
        assertEquals(FindingType.COST, new Ec2Scanner(null, null).findingType());
        assertEquals(FindingType.COST, new RdsScanner(null, null).findingType());
        assertEquals(FindingType.COST, new S3Scanner(null, null).findingType());
        assertEquals(FindingType.COST, new EbsScanner(null, null).findingType());
        assertEquals(FindingType.COST, new LambdaScanner(null, null).findingType());
    }

    @Test
    void securityScanners_declareSecurityFindingType() {
        assertEquals(FindingType.SECURITY, new IamScanner(null).findingType());
        assertEquals(FindingType.SECURITY, new KmsScanner(null, null).findingType());
        assertEquals(FindingType.SECURITY, new AcmScanner(null, null).findingType());
        assertEquals(FindingType.SECURITY, new WafScanner(null, null).findingType());
        assertEquals(FindingType.SECURITY, new CloudTrailScanner(null).findingType());
        assertEquals(FindingType.SECURITY, new SecretsManagerScanner(null, null).findingType());
    }

    @Test
    void governanceScanners_declareGovernanceFindingType() {
        assertEquals(FindingType.GOVERNANCE, new CloudFormationScanner(null, null).findingType());
        assertEquals(FindingType.GOVERNANCE, new CloudWatchScanner(null, null).findingType());
        assertEquals(FindingType.GOVERNANCE, new VpcScanner(null).findingType());
        assertEquals(FindingType.GOVERNANCE, new ParameterStoreScanner(null, null).findingType());
    }

    // --- Actual engine output matches isActionable ---

    @Test
    void iamUser_noMfa_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getIamUserRecommendation(false, 10));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "IAM user without MFA should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void kmsKey_noRotation_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getKmsRecommendation(false, "Enabled"));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "KMS key without rotation should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void cloudTrail_disabled_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getCloudTrailRecommendation(false));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Disabled CloudTrail should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void cloudWatchLogGroup_noRetention_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getCloudWatchLogGroupRecommendation(null));
        r.setFindingType(FindingType.GOVERNANCE);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Log group with no retention should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void secretsManager_noRotation_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getSecretRecommendation(10, false, false));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Secret without rotation should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void parameterStore_sensitiveExposed_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getParameterRecommendation(10, "String", true));
        r.setFindingType(FindingType.GOVERNANCE);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Sensitive parameter as plain string should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void cloudFormation_rollback_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getCloudFormationRecommendation("ROLLBACK_COMPLETE"));
        r.setFindingType(FindingType.GOVERNANCE);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Rollback stack should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void acm_expired_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getAcmRecommendation("EXPIRED", true));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "Expired certificate should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void waf_noRules_isActionable() {
        var engine = new RecommendationEngine();
        ResourceDto r = new ResourceDto();
        r.setRecommendation(engine.getWafRecommendation(true, 0));
        r.setFindingType(FindingType.SECURITY);
        assertTrue(ResourceAnalyzer.isActionable(r),
                "WAF with no rules should be actionable, got: " + r.getRecommendation());
    }

    @Test
    void activeResource_isNotActionable() {
        ResourceDto r = new ResourceDto();
        r.setRecommendation("Active");
        r.setFindingType(FindingType.SECURITY);
        assertFalse(ResourceAnalyzer.isActionable(r));
    }

    // --- Severity classification ---

    @Test
    void severity_securityExpired_isCritical() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.SECURITY);
        r.setRecommendation("Expired - Certificate Needs Renewal");
        assertEquals(Severity.CRITICAL, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_securityExposed_isCritical() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.SECURITY);
        r.setRecommendation("Misconfigured - Non-Sensitive Value in Secrets Manager");
        assertEquals(Severity.CRITICAL, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_securityEnableMfa_isHigh() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.SECURITY);
        r.setRecommendation("Enable MFA - User Lacks Multi-Factor Authentication");
        assertEquals(Severity.HIGH, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_securityRotateKey_isHigh() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.SECURITY);
        r.setRecommendation("Rotate Key - Auto-Rotation Disabled");
        assertEquals(Severity.HIGH, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_governanceStale_isMedium() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.GOVERNANCE);
        r.setRecommendation("Stale - Stack in Rollback State");
        assertEquals(Severity.MEDIUM, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_governanceMissingRetention_isMedium() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.GOVERNANCE);
        r.setRecommendation("Missing Retention Policy - Logs Never Expire");
        assertEquals(Severity.MEDIUM, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_governanceExposed_isHigh() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.GOVERNANCE);
        r.setRecommendation("Exposed - Sensitive Value Stored as Plain String");
        assertEquals(Severity.HIGH, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_costIdleHighCost_isHigh() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.COST);
        r.setRecommendation("Idle - Consider Downsizing or Terminating");
        r.setMonthlyCostUsd(75.0);
        assertEquals(Severity.HIGH, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_costIdleLowCost_isLow() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.COST);
        r.setRecommendation("Idle - No Invocations");
        r.setMonthlyCostUsd(2.0);
        assertEquals(Severity.LOW, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_active_isInfo() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.COST);
        r.setRecommendation("Active - Good Utilization");
        assertEquals(Severity.INFO, ResourceAnalyzer.classifySeverity(r));
    }

    @Test
    void severity_nullRecommendation_isInfo() {
        ResourceDto r = new ResourceDto();
        r.setFindingType(FindingType.SECURITY);
        r.setRecommendation(null);
        assertEquals(Severity.INFO, ResourceAnalyzer.classifySeverity(r));
    }
}
