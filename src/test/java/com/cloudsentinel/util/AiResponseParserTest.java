package com.cloudsentinel.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiResponseParserTest {

    // --- extractJson ---

    @Test
    void extractJson_plainJson() {
        String json = "{\"key\": \"value\"}";
        assertEquals(json, AiResponseParser.extractJson(json));
    }

    @Test
    void extractJson_markdownFence() {
        String input = "```json\n{\"key\": \"value\"}\n```";
        assertEquals("{\"key\": \"value\"}", AiResponseParser.extractJson(input));
    }

    @Test
    void extractJson_plainFence() {
        String input = "```\n{\"key\": \"value\"}\n```";
        assertEquals("{\"key\": \"value\"}", AiResponseParser.extractJson(input));
    }

    @Test
    void extractJson_withSurroundingText() {
        String input = "Here is the JSON:\n{\"key\": \"value\"}\nEnd of response.";
        assertEquals("{\"key\": \"value\"}", AiResponseParser.extractJson(input));
    }

    @Test
    void extractJson_nullInput() {
        assertEquals("", AiResponseParser.extractJson(null));
    }

    @Test
    void extractJson_blankInput() {
        assertEquals("", AiResponseParser.extractJson("   "));
    }

    @Test
    void extractJson_noJsonBraces() {
        assertEquals("just text", AiResponseParser.extractJson("just text"));
    }

    // --- sanitizeAiText ---

    @Test
    void sanitize_awsCliCommand() {
        String text = "Run aws ec2 terminate-instances to clean up.";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("aws ec2 terminate"));
        assertTrue(result.contains("[command redacted"));
    }

    @Test
    void sanitize_terraformApply() {
        String text = "Execute terraform apply to deploy.";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("terraform apply"));
        assertTrue(result.contains("[command redacted"));
    }

    @Test
    void sanitize_kubectlDelete() {
        String text = "Use kubectl delete pod to remove it.";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("kubectl delete"));
        assertTrue(result.contains("[command redacted"));
    }

    @Test
    void sanitize_rmRf() {
        String text = "You can rm -rf the directory.";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("rm -rf"));
        assertTrue(result.contains("[command redacted"));
    }

    @Test
    void sanitize_safeTextUnchanged() {
        String text = "Consider terminating this instance. The resource is idle and costs $42/mo.";
        assertEquals(text, AiResponseParser.sanitizeAiText(text));
    }

    @Test
    void sanitize_nullInput() {
        assertNull(AiResponseParser.sanitizeAiText(null));
    }

    @Test
    void sanitize_blankInput() {
        assertEquals("  ", AiResponseParser.sanitizeAiText("  "));
    }

    @Test
    void sanitize_curlDelete() {
        String text = "Try curl -X DELETE https://api.example.com/resource";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("curl -X DELETE"));
    }

    @Test
    void sanitize_dockerRm() {
        String text = "Run docker rm container-id to clean up.";
        String result = AiResponseParser.sanitizeAiText(text);
        assertFalse(result.contains("docker rm"));
    }
}
