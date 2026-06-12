package com.cloudsentinel.dto;

/**
 * Classifies the nature of a resource finding.
 *
 * <p>Every {@link ResourceDto} carries a {@code findingType} that indicates whether the finding
 * relates to cost optimization, security posture, or governance hygiene. This classification
 * drives tab-based dashboard rendering, filtering, and CSV export grouping.</p>
 *
 * <p>The mapping from scanner category to finding type is:</p>
 * <ul>
 *   <li>{@link com.cloudsentinel.service.scanner.ResourceScanner.ScanCategory#COST_OPTIMIZATION COST_OPTIMIZATION}
 *       scanners produce {@link #COST} findings.</li>
 *   <li>{@link com.cloudsentinel.service.scanner.ResourceScanner.ScanCategory#SECURITY_GOVERNANCE SECURITY_GOVERNANCE}
 *       scanners produce {@link #SECURITY} or {@link #GOVERNANCE} findings, based on the resource type.</li>
 * </ul>
 */
public enum FindingType {

    /** Cost optimization finding — idle, underutilized, or over-provisioned resources. */
    COST,

    /** Security finding — exposed credentials, missing encryption, permissive access. */
    SECURITY,

    /** Governance finding — stale resources, missing tags, orphaned artifacts, hygiene issues. */
    GOVERNANCE
}
