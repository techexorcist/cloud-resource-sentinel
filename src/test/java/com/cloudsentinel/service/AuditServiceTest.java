package com.cloudsentinel.service;

import com.cloudsentinel.service.AuditService.AuditEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditServiceTest {

    @TempDir
    Path tempDir;

    private AuditService createServiceWithDir(Path dir) throws Exception {
        AuditService service = new AuditService(90);
        // Replace the auditDir field with our temp directory
        Field auditDirField = AuditService.class.getDeclaredField("auditDir");
        auditDirField.setAccessible(true);
        auditDirField.set(service, dir);
        Files.createDirectories(dir);
        return service;
    }

    @Test
    void logScan_writesJsonFileToAuditDirectory() throws Exception {
        AuditService service = createServiceWithDir(tempDir);
        AuditEntry entry = new AuditEntry(
                Instant.parse("2025-01-15T10:30:00Z"),
                "123456789012", "prod-profile", "SUCCESS",
                50, 150.0, 10, 45.0,
                true, "ollama", 5, 12000,
                "llama3", 100, 200, 300, 5000,
                "FULL", 40, 5, 5
        );

        service.logScan(entry);

        long fileCount = Files.list(tempDir).filter(p -> p.toString().endsWith(".json")).count();
        assertEquals(1, fileCount);
    }

    @Test
    void getHistory_returnsEntriesSortedByTimestampDescending() throws Exception {
        AuditService service = createServiceWithDir(tempDir);

        AuditEntry older = new AuditEntry(
                Instant.parse("2025-01-10T10:00:00Z"),
                "111111111111", "old-profile", "SUCCESS",
                10, 50.0, 2, 10.0,
                false, null, 3, 5000,
                null, null, null, null, 0,
                "FULL", 0, 0, 0
        );
        AuditEntry newer = new AuditEntry(
                Instant.parse("2025-01-15T10:00:00Z"),
                "222222222222", "new-profile", "SUCCESS",
                20, 100.0, 5, 25.0,
                true, "bedrock", 5, 8000,
                "claude-3", 150, 250, 400, 3000,
                "FULL", 15, 3, 2
        );

        service.logScan(older);
        service.logScan(newer);

        var history = service.getHistory(10);
        assertEquals(2, history.size());
        assertTrue(history.get(0).timestamp().isAfter(history.get(1).timestamp()));
        assertEquals("222222222222", history.get(0).accountId());
    }

    @Test
    void getHistory_withLimit_returnsAtMostNEntries() throws Exception {
        AuditService service = createServiceWithDir(tempDir);

        for (int i = 0; i < 5; i++) {
            AuditEntry entry = new AuditEntry(
                    Instant.now().plusSeconds(i),
                    "acct-" + i, "profile-" + i, "SUCCESS",
                    i, i * 10.0, i, i * 5.0,
                    false, null, 1, 1000,
                    null, null, null, null, 0,
                    "FULL", 0, 0, 0
            );
            service.logScan(entry);
        }

        var history = service.getHistory(3);
        assertEquals(3, history.size());
    }

    @Test
    void getHistory_withEmptyDirectory_returnsEmptyList() throws Exception {
        AuditService service = createServiceWithDir(tempDir);
        var history = service.getHistory(10);
        assertTrue(history.isEmpty());
    }

    @Test
    void auditEntry_serializedWithSnakeCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.registerModule(new JavaTimeModule());

        AuditEntry entry = new AuditEntry(
                Instant.parse("2025-01-15T10:30:00Z"),
                "123456789012", "prod", "SUCCESS",
                50, 150.0, 10, 45.0,
                true, "ollama", 5, 12000,
                "llama3", 100, 200, 300, 5000,
                "FULL", 40, 5, 5
        );

        String json = mapper.writeValueAsString(entry);
        assertTrue(json.contains("\"account_id\""));
        assertTrue(json.contains("\"profile_name\""));
        assertTrue(json.contains("\"resource_count\""));
        assertTrue(json.contains("\"total_cost\""));
        assertTrue(json.contains("\"idle_count\""));
        assertTrue(json.contains("\"potential_savings\""));
        assertTrue(json.contains("\"ai_enabled\""));
        assertTrue(json.contains("\"ai_provider\""));
        assertTrue(json.contains("\"regions_scanned\""));
        assertTrue(json.contains("\"duration_ms\""));
        assertTrue(json.contains("\"ai_model\""));
        assertTrue(json.contains("\"prompt_tokens\""));
        assertTrue(json.contains("\"completion_tokens\""));
        assertTrue(json.contains("\"total_tokens\""));
        assertTrue(json.contains("\"ai_duration_ms\""));
        assertTrue(json.contains("\"scan_category\""));
        assertTrue(json.contains("\"cost_findings_count\""));
        assertTrue(json.contains("\"security_findings_count\""));
        assertTrue(json.contains("\"governance_findings_count\""));
        // Verify no camelCase keys
        assertFalse(json.contains("\"accountId\""));
        assertFalse(json.contains("\"profileName\""));
        assertFalse(json.contains("\"resourceCount\""));
    }

    @Test
    void auditEntry_withAiUsageFields() {
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                "123456789012", "dev", "SUCCESS",
                100, 500.0, 20, 100.0,
                true, "bedrock", 10, 30000,
                "claude-3-sonnet", 500, 1000, 1500, 15000,
                "FULL", 70, 15, 15
        );

        assertEquals("claude-3-sonnet", entry.aiModel());
        assertEquals(500, entry.promptTokens());
        assertEquals(1000, entry.completionTokens());
        assertEquals(1500, entry.totalTokens());
        assertEquals(15000, entry.aiDurationMs());
        assertTrue(entry.aiEnabled());
        assertEquals("bedrock", entry.aiProvider());
    }

    @Test
    void auditEntry_roundtripSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        AuditEntry original = new AuditEntry(
                Instant.parse("2025-06-01T12:00:00Z"),
                "999888777666", "round-trip", "FAILED",
                0, 0.0, 0, 0.0,
                false, null, 1, 500,
                null, null, null, null, 0,
                "FULL", 0, 0, 0
        );

        String json = mapper.writeValueAsString(original);
        AuditEntry deserialized = mapper.readValue(json, AuditEntry.class);

        assertEquals(original.accountId(), deserialized.accountId());
        assertEquals(original.profileName(), deserialized.profileName());
        assertEquals(original.status(), deserialized.status());
        assertEquals(original.resourceCount(), deserialized.resourceCount());
        assertEquals(original.aiEnabled(), deserialized.aiEnabled());
    }
}
