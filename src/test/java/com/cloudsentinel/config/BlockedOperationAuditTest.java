package com.cloudsentinel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BlockedOperationAuditTest {

    @TempDir
    Path tempDir;

    private BlockedOperationAudit createWithDir(Path dir) throws Exception {
        var audit = new BlockedOperationAudit();
        Field fileField = BlockedOperationAudit.class.getDeclaredField("auditFile");
        fileField.setAccessible(true);
        fileField.set(audit, dir.resolve("blocked-operations.jsonl"));
        return audit;
    }

    @Test
    void recordBlocked_appendsToFile() throws Exception {
        var audit = createWithDir(tempDir);
        audit.recordBlocked("DeleteBucket", "at com.test.SomeClass.method(SomeClass.java:42)");

        Path file = tempDir.resolve("blocked-operations.jsonl");
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("DeleteBucket"));
        assertTrue(content.contains("SomeClass"));
    }

    @Test
    void recordBlocked_incrementsCount() throws Exception {
        var audit = createWithDir(tempDir);
        assertEquals(0, audit.getBlockedCount());

        audit.recordBlocked("TerminateInstances", "trace1");
        assertEquals(1, audit.getBlockedCount());

        audit.recordBlocked("DeleteDBInstance", "trace2");
        assertEquals(2, audit.getBlockedCount());
    }

    @Test
    void recordBlocked_multipleEntries_allPersisted() throws Exception {
        var audit = createWithDir(tempDir);
        audit.recordBlocked("DeleteBucket", "trace1");
        audit.recordBlocked("TerminateInstances", "trace2");
        audit.recordBlocked("RunInstances", "trace3");

        Path file = tempDir.resolve("blocked-operations.jsonl");
        long lineCount = Files.lines(file).count();
        assertEquals(3, lineCount);
    }

    @Test
    void recordBlocked_escapesQuotesInStackTrace() throws Exception {
        var audit = createWithDir(tempDir);
        audit.recordBlocked("PutObject", "at \"some.Class\"");

        Path file = tempDir.resolve("blocked-operations.jsonl");
        String content = Files.readString(file);
        // Quotes should be escaped for valid JSON
        assertFalse(content.contains("\"some.Class\""), "Unescaped quotes would break JSON");
    }
}
