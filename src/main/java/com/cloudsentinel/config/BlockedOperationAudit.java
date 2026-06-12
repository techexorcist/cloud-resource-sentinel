package com.cloudsentinel.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Layer 7: Persistent forensic audit log for blocked AWS API operations.
 *
 * <p>Every time {@link ReadOnlyInterceptor} blocks a mutating API call, this class
 * appends a structured JSONL entry to {@code ~/.cloud-sentinel/blocked-operations.jsonl}.
 * The file is append-only — the application never overwrites or truncates it.</p>
 *
 * <p>This provides detection alongside prevention: if something repeatedly triggers
 * blocks, it signals either a bug or an attack. The blocked count is exposed via
 * the {@link #getBlockedCount()} method for Actuator integration.</p>
 */
@Component
public class BlockedOperationAudit {

    private static final Logger log = LoggerFactory.getLogger(BlockedOperationAudit.class);
    private final Path auditFile;
    private final AtomicLong blockedCount = new AtomicLong(0);

    public BlockedOperationAudit() {
        this.auditFile = Path.of(System.getProperty("user.home"), ".cloud-sentinel", "blocked-operations.jsonl");
        try {
            Files.createDirectories(auditFile.getParent());
            // Count existing entries on startup
            if (Files.exists(auditFile)) {
                blockedCount.set(Files.lines(auditFile).count());
                if (blockedCount.get() > 0) {
                    log.warn("Found {} previously blocked operations in audit log: {}", blockedCount.get(), auditFile);
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize blocked operations audit: {}", e.getMessage());
        }
    }

    /**
     * Records a blocked operation to the persistent audit log.
     *
     * @param operationName the AWS API operation that was blocked
     * @param stackTrace    the call-site stack trace for forensic analysis
     */
    public void recordBlocked(String operationName, String stackTrace) {
        blockedCount.incrementAndGet();
        String entry = String.format(
                "{\"timestamp\":\"%s\",\"operation\":\"%s\",\"stack_trace\":\"%s\"}%n",
                Instant.now(), operationName, stackTrace.replace("\"", "\\\"").replace("\n", "\\n"));
        try {
            Files.writeString(auditFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to write blocked operation audit entry: {}", e.getMessage());
        }
    }

    /** Returns the total number of blocked operations since the audit file was created. */
    public long getBlockedCount() { return blockedCount.get(); }

    /** Returns the path to the audit file. */
    public Path getAuditFilePath() { return auditFile; }
}
