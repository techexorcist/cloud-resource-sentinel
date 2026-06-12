package com.cloudsentinel.dto;

/**
 * Classifies the urgency of a resource finding.
 *
 * <p>For {@link FindingType#COST} findings, severity maps to potential savings impact.
 * For {@link FindingType#SECURITY} and {@link FindingType#GOVERNANCE} findings, severity
 * indicates risk level independent of dollar cost.</p>
 */
public enum Severity {

    /** Informational — no action required (e.g., active resource, healthy state). */
    INFO,

    /** Low priority — minor issue, address when convenient. */
    LOW,

    /** Medium priority — should be addressed in normal workflow. */
    MEDIUM,

    /** High priority — address promptly (e.g., exposed credentials, disabled logging). */
    HIGH,

    /** Critical — immediate action required (e.g., expired certificate in production). */
    CRITICAL
}
