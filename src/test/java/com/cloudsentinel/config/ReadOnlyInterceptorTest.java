package com.cloudsentinel.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the ReadOnlyInterceptor correctly allows read operations
 * and blocks all mutating operations.
 */
class ReadOnlyInterceptorTest {

    @Test
    void allowsDescribeOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("DescribeInstances"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("DescribeDBInstances"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("DescribeReservedInstances"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("DescribeAlarms"));
    }

    @Test
    void allowsListOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("ListBuckets"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("ListClusters"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("ListTables"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("ListFunctions"));
    }

    @Test
    void allowsGetOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetCallerIdentity"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetMetricStatistics"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetBucketLocation"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetQueueAttributes"));
    }

    @Test
    void allowsStsOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("AssumeRole"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetSessionToken"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("GetCallerIdentity"));
    }

    @Test
    void allowsOtherReadOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("SearchResources"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("BatchGetItem"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("ScanTable"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("HeadObject"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("HeadBucket"));
    }

    @Test
    void blocksDeleteOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteBucket"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteInstance"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteDBInstance"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteTable"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteFunction"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeleteAlarm"));
    }

    @Test
    void blocksTerminateOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("TerminateInstances"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("TerminateJobFlows"));
    }

    @Test
    void blocksCreateOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("CreateBucket"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("CreateDBInstance"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("CreateFunction"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("CreateTable"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("RunInstances"));
    }

    @Test
    void blocksModifyOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("ModifyDBInstance"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("ModifyInstanceAttribute"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("UpdateTable"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("UpdateFunction"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("UpdateStack"));
    }

    @Test
    void blocksPutOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("PutObject"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("PutMetricAlarm"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("PutBucketPolicy"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("PutItem"));
    }

    @Test
    void blocksStopAndStartOperations() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("StopInstances"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("StartInstances"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("RebootInstances"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("StopDBInstance"));
    }

    @Test
    void blocksDestructiveActions() {
        assertFalse(ReadOnlyInterceptor.isReadOnly("DeregisterImage"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("RevokeSecurityGroupIngress"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("AttachVolume"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DetachVolume"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("ReleaseAddress"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("DisassociateAddress"));
    }

    @Test
    void blocksDangerousExportTestSimulatePreview() {
        // These prefixes were removed from the allowlist because some services
        // use them for mutating operations
        assertFalse(ReadOnlyInterceptor.isReadOnly("ExportImage"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("ExportTransitGatewayRoutes"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("TestInvokeAuthorizer"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("TestMigration"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("SimulateExecution"));
        assertFalse(ReadOnlyInterceptor.isReadOnly("PreviewActionsRequired"));
    }

    @Test
    void allowsSafeExactNameOperations() {
        assertTrue(ReadOnlyInterceptor.isReadOnly("GenerateDataKey"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("TestEventPattern"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("SimulateCustomPolicy"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("SimulatePrincipalPolicy"));
        assertTrue(ReadOnlyInterceptor.isReadOnly("PreviewAgents"));
    }

    // ══════════════════════════════════════════════════════════════════
    // Layer 8: Property-based tests — comprehensive coverage of ALL known
    // dangerous AWS verb patterns to catch edge cases the manual tests miss.
    // ══════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
            // Every known dangerous verb prefix across all AWS services
            "RunInstances", "RunTask", "RunJobFlow", "RunPipelineActivity",
            "LaunchInstances", "ImportImage", "ImportSnapshot",
            "EnableVpcClassicLink", "EnableTransitGatewayRouteTablePropagation",
            "DisableVpcClassicLink", "DisableLogging",
            "RegisterImage", "RegisterTaskDefinition",
            "DeregisterImage", "DeregisterTaskDefinition",
            "AuthorizeSecurityGroupIngress", "AuthorizeSecurityGroupEgress",
            "RevokeSecurityGroupIngress", "RevokeSecurityGroupEgress",
            "AssociateAddress", "AssociateRouteTable",
            "DisassociateAddress", "DisassociateRouteTable",
            "AllocateAddress", "AllocateHosts",
            "ReleaseAddress", "ReleaseHosts",
            "AcceptVpcPeeringConnection", "RejectVpcPeeringConnection",
            "CopyImage", "CopySnapshot",
            "MoveAddressToVpc",
            "RestoreDBInstanceFromDBSnapshot", "RestoreDBClusterFromSnapshot",
            "PromoteReadReplica",
            "RebootInstances", "RebootDBInstance", "RebootCacheCluster",
            "TagResource", "UntagResource",
            "SendMessage", "SendEmail", "PublishMessage",
            "InvokeFunction", "InvokeEndpoint",
            "ExecuteStatement", "ExecuteApi",
            "PurchaseReservedInstancesOffering", "PurchaseHostReservation",
            "CancelSpotInstanceRequests", "CancelReservedInstancesListing",
            "RequestSpotInstances", "RequestCertificate",
            "RotateSecret", "RestoreSecret",
            "ScheduleKeyDeletion", "CancelKeyDeletion",
            "EncryptData", "SignPayload",
            "SetAlarmState", "SetRuleState",
            "WriteRecords", "BatchWriteItem",
            "EmptyBucket", "DeleteObjects"
    })
    void blocksAllKnownDangerousVerbs(String operation) {
        assertFalse(ReadOnlyInterceptor.isReadOnly(operation),
                "Should block dangerous operation: " + operation);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // All read-only prefixes should work with any suffix
            "DescribeAnything", "ListAnything", "GetAnything",
            "SearchAnything", "LookupAnything", "BatchGetAnything",
            "ScanAnything", "QueryAnything", "HeadAnything",
            "CheckAnything", "CountAnything", "EstimateAnything",
            "ValidateAnything", "DownloadAnything"
    })
    void allowsAllReadOnlyPrefixes(String operation) {
        assertTrue(ReadOnlyInterceptor.isReadOnly(operation),
                "Should allow read-only operation: " + operation);
    }
}
