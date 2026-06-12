package com.cloudsentinel.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for IamPolicyVerifier. Since the verifier calls STS at runtime,
 * these tests verify the class structure and disabled-mode behavior.
 * Full integration testing requires real AWS credentials.
 */
class IamPolicyVerifierTest {

    @Test
    void verifier_isInstantiable() {
        // Verifies the class can be constructed without Spring context
        var verifier = new IamPolicyVerifier();
        assertNotNull(verifier);
    }

    @Test
    void verifier_disabledMode_doesNotThrow() throws Exception {
        // When disabled, verifyIamPolicy should return immediately
        var verifier = new IamPolicyVerifier();
        var enabledField = IamPolicyVerifier.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(verifier, false);

        // Should not throw — just returns
        assertDoesNotThrow(verifier::verifyIamPolicy);
    }

    @Test
    void verifier_noCredentials_doesNotThrow() {
        // When no AWS credentials are available, should log warning but not crash
        var verifier = new IamPolicyVerifier();
        // In a test environment without AWS credentials, this should handle gracefully
        assertDoesNotThrow(verifier::verifyIamPolicy);
    }
}
