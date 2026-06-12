package com.cloudsentinel.controller;

import com.cloudsentinel.service.AuditService;
import com.cloudsentinel.service.AuditService.AuditEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AuditController controller;

    @Test
    void getAuditHistory_returnsList() {
        var entry = new AuditEntry(
                Instant.now(), "123456789012", "test-profile", "complete",
                50, 100.0, 10, 30.0,
                true, "bedrock", 5, 15000,
                "claude-sonnet", 100, 200, 300, 5000,
                "FULL", 40, 5, 5
        );
        when(auditService.getHistory(50)).thenReturn(List.of(entry));

        var result = controller.getAuditHistory(50);
        assertEquals(1, result.size());
        assertEquals("123456789012", result.getFirst().accountId());
        assertEquals("complete", result.getFirst().status());
    }

    @Test
    void getAuditHistory_emptyList() {
        when(auditService.getHistory(10)).thenReturn(List.of());

        var result = controller.getAuditHistory(10);
        assertTrue(result.isEmpty());
    }
}
