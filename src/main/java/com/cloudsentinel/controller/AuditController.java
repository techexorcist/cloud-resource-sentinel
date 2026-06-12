package com.cloudsentinel.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.service.AuditService;
import com.cloudsentinel.service.AuditService.AuditEntry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Audit API group, providing access to the scan audit trail.
 *
 * <p>This controller exposes the scan history with timestamps, accounts, statuses, and metrics,
 * allowing operators to review past scan activity.</p>
 *
 * @see com.cloudsentinel.service.AuditService
 */
@RestController
@Tag(name = "Audit")
public class AuditController {

    private final AuditService auditService;

    /**
     * Constructs the audit controller with its required service dependency.
     *
     * @param auditService the audit service for scan history tracking
     */
    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Returns the scan audit trail with timestamps, accounts, statuses, and metrics.
     *
     * <p>GET {@code /analyse/audit?limit=50}</p>
     *
     * @param limit the maximum number of audit entries to return (default: 50)
     * @return a list of audit entries, newest first
     */
    @Operation(summary = "Get audit history", description = "Returns the scan audit trail with timestamps, accounts, statuses, and metrics.")
    @GetMapping("/analyse/audit")
    public List<AuditEntry> getAuditHistory(@RequestParam(defaultValue = "50") int limit) {
        return auditService.getHistory(limit);
    }
}
