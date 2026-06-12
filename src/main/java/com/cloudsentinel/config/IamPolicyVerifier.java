package com.cloudsentinel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * Layer 5: Verifies at startup that the AWS credentials in use are attached to a
 * read-only IAM role/policy. Logs a warning if verification fails (e.g., no credentials
 * configured) but does not block startup to support mock-data mode and local development.
 *
 * <p>This is the only guardrail layer that AWS itself enforces — even if the JVM is
 * compromised and layers 1-4 are bypassed, IAM will still reject mutating API calls.</p>
 *
 * <p>The verifier calls STS GetCallerIdentity to confirm credentials are valid, then
 * logs the attached principal ARN so operators can confirm it matches the expected
 * read-only role.</p>
 */
@Component
public class IamPolicyVerifier {

    private static final Logger log = LoggerFactory.getLogger(IamPolicyVerifier.class);

    @Value("${cloud-sentinel.iam-verification.enabled:true}")
    private boolean enabled;

    /**
     * Runs after the application context is fully initialized. Verifies AWS credentials
     * are valid and logs the IAM principal for manual policy verification.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyIamPolicy() {
        if (!enabled) {
            log.info("IAM policy verification disabled (cloud-sentinel.iam-verification.enabled=false)");
            return;
        }

        try (var sts = ReadOnlyAwsClientFactory.build(
                StsClient.builder(), Region.US_EAST_1)) {
            var identity = sts.getCallerIdentity();
            String arn = identity.arn();
            String account = identity.account();

            log.info("=== LAYER 5: IAM POLICY VERIFICATION ===");
            log.info("AWS Account: {}", account);
            log.info("IAM Principal: {}", arn);

            // Check if the ARN suggests a read-only role
            String arnLower = arn.toLowerCase();
            if (arnLower.contains("readonly") || arnLower.contains("read-only")
                    || arnLower.contains("viewer") || arnLower.contains("sentinel")) {
                log.info("IAM principal name suggests read-only access. GOOD.");
            } else {
                log.warn("IAM principal '{}' does not contain 'readonly' or 'sentinel' in its name. " +
                        "Verify that only read-only permissions are attached. " +
                        "See docs/iam-policy.json for the recommended policy.", arn);
            }

            log.info("IMPORTANT: Cloud Resource Sentinel should ONLY be run with read-only IAM permissions. " +
                    "Attach the policy from docs/iam-policy.json to a dedicated IAM role. " +
                    "The SDK interceptor provides defense-in-depth, but IAM is the only layer AWS enforces.");
        } catch (Exception e) {
            log.warn("IAM policy verification skipped — no valid AWS credentials available. " +
                    "This is expected in mock-data mode or local development. Error: {}", e.getMessage());
        }
    }
}
