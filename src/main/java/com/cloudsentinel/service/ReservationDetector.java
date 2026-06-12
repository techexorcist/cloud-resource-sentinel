package com.cloudsentinel.service;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.ResourceDto;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeReservedInstancesResponse;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.DescribeReservedCacheNodesRequest;
import software.amazon.awssdk.services.elasticache.model.DescribeReservedCacheNodesResponse;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DescribeReservedDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeReservedDbInstancesResponse;
import software.amazon.awssdk.services.savingsplans.SavingsplansClient;
import software.amazon.awssdk.services.savingsplans.model.DescribeSavingsPlansRequest;
import software.amazon.awssdk.services.savingsplans.model.DescribeSavingsPlansResponse;
import software.amazon.awssdk.services.savingsplans.model.SavingsPlanState;

/**
 * Detects Reserved Instances (RIs) and Savings Plans across AWS services and overlays
 * coverage information onto scanned resources.
 *
 * <p>After the resource scanners complete, this service queries each region for active
 * reservations (EC2 Reserved Instances, RDS Reserved DB Instances, ElastiCache Reserved
 * Cache Nodes) and checks for active Savings Plans (queried globally from us-east-1).
 * Resources that match a reservation are tagged with {@code coveredBy = "RI"}, and
 * EC2/ElastiCache resources eligible for Savings Plans coverage are tagged with
 * {@code coveredBy = "Savings Plan"}.</p>
 *
 * <p>All AWS clients are built through {@link com.cloudsentinel.config.ReadOnlyAwsClientFactory}
 * to enforce the read-only security guardrail.</p>
 *
 * <p>Failures in reservation detection are logged at DEBUG level and silently swallowed
 * to avoid disrupting the main scan flow. Missing reservation data simply means the
 * {@code coveredBy} field remains {@code null}.</p>
 *
 * @see ResourceAnalyzer#analyzeAllResources
 */
@Service
public class ReservationDetector {

    private static final Logger log = LoggerFactory.getLogger(ReservationDetector.class);

    /**
     * Queries all reservation types across the specified regions and annotates matching
     * resources with their coverage type.
     *
     * <p>The overlay is applied in two passes:</p>
     * <ol>
     *   <li>Per-region: collects active EC2 RIs, RDS RIs, and ElastiCache reserved nodes,
     *       building a map of resource type to reserved instance types.</li>
     *   <li>Global: checks for active Savings Plans (always queried from us-east-1).</li>
     * </ol>
     *
     * <p>Resources are then matched by resource type and instance type. RI matches take
     * priority; Savings Plan coverage is applied only to EC2 and ElastiCache resources
     * that are not already covered by an RI.</p>
     *
     * @param resources the mutable list of scanned resources to annotate
     * @param creds     the AWS credentials provider
     * @param regions   the list of AWS regions to check for reservations
     */
    public void overlayReservationData(List<ResourceDto> resources, AwsCredentialsProvider creds, List<String> regions) {
        try {
            Map<String, Set<String>> reservedTypes = new HashMap<>();
            boolean hasSavingsPlans = false;

            for (String regionName : regions) {
                Region region = Region.of(regionName);
                collectEc2Reservations(reservedTypes, creds, region);
                collectRdsReservations(reservedTypes, creds, region);
                collectElastiCacheReservations(reservedTypes, creds, region);
            }

            hasSavingsPlans = checkSavingsPlans(creds);

            for (ResourceDto resource : resources) {
                String resourceType = resource.getResourceType();
                String instanceType = resource.getInstanceType();

                if (instanceType != null && reservedTypes.containsKey(resourceType)) {
                    if (reservedTypes.get(resourceType).contains(instanceType)) {
                        resource.setCoveredBy("RI");
                    }
                }

                if (hasSavingsPlans && ("EC2".equals(resourceType) || "ElastiCache".equals(resourceType))
                        && resource.getCoveredBy() == null) {
                    resource.setCoveredBy("Savings Plan");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to overlay reservation data", e);
        }
    }

    /**
     * Queries active EC2 Reserved Instances in the given region and adds their instance types
     * to the reservation map.
     *
     * @param reservedTypes the map to populate (key: resource type, value: set of reserved instance types)
     * @param creds         the AWS credentials provider
     * @param region        the AWS region to query
     */
    private void collectEc2Reservations(Map<String, Set<String>> reservedTypes, AwsCredentialsProvider creds, Region region) {
        try (Ec2Client ec2 = ReadOnlyAwsClientFactory.build(Ec2Client.builder(), creds, region)) {
            DescribeReservedInstancesResponse response = ec2.describeReservedInstances(
                    DescribeReservedInstancesRequest.builder()
                            .filters(f -> f.name("state").values("active"))
                            .build()
            );
            response.reservedInstances().forEach(ri ->
                    reservedTypes.computeIfAbsent("EC2", k -> new HashSet<>()).add(ri.instanceType().toString())
            );
        } catch (Exception e) {
            log.debug("Failed to check EC2 reserved instances in {}", region, e);
        }
    }

    /**
     * Queries active RDS Reserved DB Instances in the given region and adds their DB instance
     * classes to the reservation map.
     *
     * @param reservedTypes the map to populate
     * @param creds         the AWS credentials provider
     * @param region        the AWS region to query
     */
    private void collectRdsReservations(Map<String, Set<String>> reservedTypes, AwsCredentialsProvider creds, Region region) {
        try (RdsClient rds = ReadOnlyAwsClientFactory.build(RdsClient.builder(), creds, region)) {
            DescribeReservedDbInstancesResponse response = rds.describeReservedDBInstances(
                    DescribeReservedDbInstancesRequest.builder().build()
            );
            response.reservedDBInstances().stream()
                    .filter(ri -> "active".equalsIgnoreCase(ri.state()))
                    .forEach(ri ->
                            reservedTypes.computeIfAbsent("RDS", k -> new HashSet<>()).add(ri.dbInstanceClass())
                    );
        } catch (Exception e) {
            log.debug("Failed to check RDS reserved instances in {}", region, e);
        }
    }

    /**
     * Queries active ElastiCache Reserved Cache Nodes in the given region and adds their
     * cache node types to the reservation map.
     *
     * @param reservedTypes the map to populate
     * @param creds         the AWS credentials provider
     * @param region        the AWS region to query
     */
    private void collectElastiCacheReservations(Map<String, Set<String>> reservedTypes, AwsCredentialsProvider creds, Region region) {
        try (ElastiCacheClient elastiCache = ReadOnlyAwsClientFactory.build(ElastiCacheClient.builder(), creds, region)) {
            DescribeReservedCacheNodesResponse response = elastiCache.describeReservedCacheNodes(
                    DescribeReservedCacheNodesRequest.builder().build()
            );
            response.reservedCacheNodes().stream()
                    .filter(rn -> "active".equalsIgnoreCase(rn.state()))
                    .forEach(rn ->
                            reservedTypes.computeIfAbsent("ElastiCache", k -> new HashSet<>()).add(rn.cacheNodeType())
                    );
        } catch (Exception e) {
            log.debug("Failed to check ElastiCache reserved nodes in {}", region, e);
        }
    }

    /**
     * Checks for active Savings Plans in the AWS account.
     *
     * <p>Savings Plans are a global construct and are always queried from {@code us-east-1}.</p>
     *
     * @param creds the AWS credentials provider
     * @return {@code true} if at least one active Savings Plan exists
     */
    private boolean checkSavingsPlans(AwsCredentialsProvider creds) {
        try (SavingsplansClient sp = ReadOnlyAwsClientFactory.build(SavingsplansClient.builder(), creds, Region.US_EAST_1)) {
            DescribeSavingsPlansResponse response = sp.describeSavingsPlans(
                    DescribeSavingsPlansRequest.builder()
                            .states(SavingsPlanState.ACTIVE)
                            .build()
            );
            return !response.savingsPlans().isEmpty();
        } catch (Exception e) {
            log.debug("Failed to check savings plans", e);
            return false;
        }
    }
}
