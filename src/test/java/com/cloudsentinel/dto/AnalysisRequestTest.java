package com.cloudsentinel.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisRequestTest {

    @Test
    void validate_withProfileName_noException() {
        var request = new AnalysisRequest(null, "my-profile", List.of("us-east-1"), null, null, null, null);
        assertDoesNotThrow(request::validate);
    }

    @Test
    void validate_withCredentials_noException() {
        var creds = new AwsCredentialsDto("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", null, null);
        var request = new AnalysisRequest(creds, null, List.of(), null, null, null, null);
        assertDoesNotThrow(request::validate);
    }

    @Test
    void validate_withNeitherProfileNorCredentials_throws() {
        var request = new AnalysisRequest(null, null, List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("Either profileName or credentials"));
    }

    @Test
    void validate_withBlankProfileAndNullCredentials_throws() {
        var request = new AnalysisRequest(null, "   ", List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("Either profileName or credentials"));
    }

    @Test
    void validate_credentialsMissingAccessKeyId_throws() {
        var creds = new AwsCredentialsDto(null, "secret", null, null);
        var request = new AnalysisRequest(creds, null, List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("accessKeyId"));
    }

    @Test
    void validate_credentialsMissingSecretAccessKey_throws() {
        var creds = new AwsCredentialsDto("AKIAIOSFODNN7EXAMPLE", null, null, null);
        var request = new AnalysisRequest(creds, null, List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("secretAccessKey"));
    }

    @Test
    void validate_credentialsBlankAccessKeyId_throws() {
        var creds = new AwsCredentialsDto("  ", "secret", null, null);
        var request = new AnalysisRequest(creds, null, List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("accessKeyId"));
    }

    @Test
    void validate_credentialsBlankSecretAccessKey_throws() {
        var creds = new AwsCredentialsDto("AKIAIOSFODNN7EXAMPLE", "  ", null, null);
        var request = new AnalysisRequest(creds, null, List.of(), null, null, null, null);
        var ex = assertThrows(IllegalArgumentException.class, request::validate);
        assertTrue(ex.getMessage().contains("secretAccessKey"));
    }

    @Test
    void isAiFilterEnabled_true() {
        var request = new AnalysisRequest(null, "prof", List.of(), true, null, null, null);
        assertTrue(request.isAiFilterEnabled());
    }

    @Test
    void isAiFilterEnabled_false() {
        var request = new AnalysisRequest(null, "prof", List.of(), false, null, null, null);
        assertFalse(request.isAiFilterEnabled());
    }

    @Test
    void isAiFilterEnabled_null_returnsFalse() {
        var request = new AnalysisRequest(null, "prof", List.of(), null, null, null, null);
        assertFalse(request.isAiFilterEnabled());
    }

    @Test
    void resolvedAiProvider_withValue() {
        var request = new AnalysisRequest(null, "prof", List.of(), null, "ollama", null, null);
        assertEquals("ollama", request.resolvedAiProvider());
    }

    @Test
    void resolvedAiProvider_withBlank_returnsNull() {
        var request = new AnalysisRequest(null, "prof", List.of(), null, "  ", null, null);
        assertNull(request.resolvedAiProvider());
    }

    @Test
    void resolvedAiProvider_withNull_returnsNull() {
        var request = new AnalysisRequest(null, "prof", List.of(), null, null, null, null);
        assertNull(request.resolvedAiProvider());
    }
}
