package com.cloudsentinel.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture guardrail test: ensures ALL AWS SDK client construction
 * goes through {@link ReadOnlyAwsClientFactory}.
 *
 * If this test fails, a developer has created an AWS client directly
 * (e.g. Ec2Client.builder()...build()) without the read-only interceptor.
 * Fix: use ReadOnlyAwsClientFactory.build(XxxClient.builder(), creds, region).
 */
class ReadOnlyGuardrailTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/cloudsentinel");

    @Test
    void allAwsClientsMustUseReadOnlyFactory() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                 .filter(p -> !p.toString().contains("ReadOnlyAwsClientFactory"))
                 .filter(p -> !p.toString().contains("ReadOnlyInterceptor"))
                 .forEach(path -> {
                     try {
                         List<String> lines = Files.readAllLines(path);
                         for (int i = 0; i < lines.size(); i++) {
                             String line = lines.get(i);
                             // Match: XxxClient.builder() NOT inside ReadOnlyAwsClientFactory.build(...)
                             if (line.matches(".*\\w+Client\\.builder\\(\\).*")
                                     && !line.contains("ReadOnlyAwsClientFactory.build(")
                                     && !line.contains("ChatModel")
                                     && !line.contains("//")) {
                                 // Check if previous line has the factory call (multi-line invocation)
                                 boolean prevLineHasFactory = i > 0 && lines.get(i - 1).contains("ReadOnlyAwsClientFactory.build(");
                                 if (!prevLineHasFactory) {
                                     violations.add(path.getFileName() + ":" + (i + 1) + " → " + line.trim());
                                 }
                             }
                         }
                     } catch (IOException e) {
                         throw new RuntimeException(e);
                     }
                 });
        }

        assertTrue(violations.isEmpty(),
                "AWS clients MUST be created through ReadOnlyAwsClientFactory.\n" +
                "The following files create AWS clients directly (bypassing read-only guardrails):\n\n" +
                String.join("\n", violations) +
                "\n\nFix: ReadOnlyAwsClientFactory.build(XxxClient.builder(), creds, region)");
    }
}
