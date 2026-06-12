package com.cloudsentinel.config;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkInternalExecutionAttribute;

/**
 * AWS SDK interceptor that blocks all mutating API calls.
 *
 * This is the primary safety guardrail ensuring Cloud Resource Sentinel
 * can NEVER modify, delete, or create AWS resources. It inspects every
 * API call before it reaches the network and rejects anything that isn't
 * a read-only operation.
 *
 * Allowed operation prefixes: Describe, List, Get, Search, Lookup,
 * BatchGet, Scan, Query, Head, Check, Count, Estimate, Validate, Download.
 * Additional safe operations are allowed by exact name (GenerateDataKey, STS operations, etc.).
 * Export*, Test*, Simulate*, Preview* are NOT allowed as prefixes — some services use these
 * for mutating operations. Specific safe operations are allowlisted by exact name instead.
 *
 * Everything else is blocked and throws a SecurityException.
 */
public final class ReadOnlyInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyInterceptor.class);

    // Conservative prefix list — only prefixes that are universally read-only across all AWS services.
    // Export*, Test*, Simulate*, Preview* were removed because some services use these for
    // mutating operations (e.g., ExportImage creates S3 artifacts, TestInvokeAuthorizer invokes Lambda,
    // SimulateExecution starts a Step Functions execution).
    private static final Set<String> ALLOWED_PREFIXES = Set.of(
            "Describe", "List", "Get", "Search", "Lookup",
            "BatchGet", "Scan", "Query", "Head", "Check",
            "Count", "Estimate", "Validate", "Download"
    );

    // Exact operation names that are safe but don't match the prefix list.
    // GenerateDataKey is the read-path of KMS encryption (returns a data key, doesn't modify the CMK).
    private static final Set<String> ALLOWED_EXACT = Set.of(
            "AssumeRole", "AssumeRoleWithSAML", "AssumeRoleWithWebIdentity",
            "GetCallerIdentity", "DecodeAuthorizationMessage",
            "GetSessionToken", "GetFederationToken",
            "SelectObjectContent", "HeadObject", "HeadBucket",
            "GenerateDataKey",
            // Safe Test/Simulate/Preview operations used by this project
            "TestEventPattern",
            "SimulateCustomPolicy", "SimulatePrincipalPolicy",
            "PreviewAgents"
    );

    /**
     * Intercepts every AWS SDK API call before it is sent to the network and blocks
     * any operation that is not read-only.
     *
     * <p>The operation name is extracted from the SDK execution attributes. If the operation
     * matches a known read-only prefix (e.g., {@code Describe*}, {@code List*}, {@code Get*})
     * or an explicitly allowed exact name (e.g., {@code GetCallerIdentity}, {@code AssumeRole}),
     * the call proceeds normally. All other operations are blocked with a {@link SecurityException}.</p>
     *
     * <p>If the operation name cannot be determined (null), the call is allowed through as it
     * typically represents SDK-internal bookkeeping rather than an actual API request.</p>
     *
     * @param context    the before-execution context containing the SDK request
     * @param attributes the execution attributes including the operation name
     * @throws SecurityException if the operation is not read-only
     */
    @Override
    public void beforeExecution(Context.BeforeExecution context, ExecutionAttributes attributes) {
        String operationName = attributes
                .getOptionalAttribute(SdkInternalExecutionAttribute.OPERATION_NAME)
                .orElse(null);

        if (operationName == null) {
            // Cannot determine operation — allow (SDK internal calls like auth handshake).
            // Log at debug level for auditability.
            log.debug("AWS SDK call with null operation name — allowing (assumed SDK-internal)");
            return;
        }

        if (isReadOnly(operationName)) {
            return;
        }

        log.error("BLOCKED mutating AWS API call: {}. Cloud Resource Sentinel is read-only.",
                operationName, new RuntimeException("call-site trace"));
        // Layer 7: Persist to forensic audit log
        try {
            var audit = ApplicationContextProvider.getBean(BlockedOperationAudit.class);
            if (audit != null) {
                var trace = new RuntimeException("call-site");
                var sw = new java.io.StringWriter();
                trace.printStackTrace(new java.io.PrintWriter(sw));
                audit.recordBlocked(operationName, sw.toString());
            }
        } catch (Exception ignored) {
            // Audit is best-effort — never let it prevent the SecurityException from firing
        }
        throw new SecurityException(
                "BLOCKED: Cloud Resource Sentinel is read-only. " +
                "Mutating operation '" + operationName + "' is not permitted. " +
                "This tool only reads AWS resources — it never creates, modifies, or deletes them."
        );
    }

    /**
     * Determines whether the given AWS API operation name represents a read-only action.
     *
     * <p>An operation is considered read-only if it either matches one of the
     * {@link #ALLOWED_EXACT} operation names exactly, or begins with one of the
     * {@link #ALLOWED_PREFIXES} (e.g., {@code Describe}, {@code List}, {@code Get}).</p>
     *
     * @param operationName the AWS API operation name to check (e.g., {@code "DescribeInstances"},
     *                      {@code "TerminateInstances"})
     * @return {@code true} if the operation is read-only and should be allowed;
     *         {@code false} if it is a mutating operation that should be blocked
     */
    static boolean isReadOnly(String operationName) {
        return ALLOWED_EXACT.contains(operationName)
                || ALLOWED_PREFIXES.stream().anyMatch(operationName::startsWith);
    }
}
