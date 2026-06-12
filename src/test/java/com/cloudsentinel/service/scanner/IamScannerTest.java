package com.cloudsentinel.service.scanner;

import com.cloudsentinel.config.ReadOnlyAwsClientFactory;
import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.RecommendationEngine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKeyMetadata;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedRequest;
import software.amazon.awssdk.services.iam.model.GetAccessKeyLastUsedResponse;
import software.amazon.awssdk.services.iam.model.ListAccessKeysRequest;
import software.amazon.awssdk.services.iam.model.ListAccessKeysResponse;
import software.amazon.awssdk.services.iam.model.ListMfaDevicesRequest;
import software.amazon.awssdk.services.iam.model.ListMfaDevicesResponse;
import software.amazon.awssdk.services.iam.model.MFADevice;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.iam.paginators.ListRolesIterable;
import software.amazon.awssdk.services.iam.paginators.ListUsersIterable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IamScannerTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void scan_userWithoutMfa_returnsEnableMfa() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockIam = mock(IamClient.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockIam);

            User user = User.builder()
                    .userId("AIDA12345")
                    .userName("dev-user")
                    .passwordLastUsed(Instant.now().minus(10, ChronoUnit.DAYS))
                    .createDate(Instant.now().minus(365, ChronoUnit.DAYS))
                    .build();

            var usersPaginator = mock(ListUsersIterable.class);
            when(mockIam.listUsersPaginator()).thenReturn(usersPaginator);
            when(usersPaginator.users()).thenReturn(() -> List.of(user).iterator());

            // No roles
            var rolesPaginator = mock(ListRolesIterable.class);
            when(mockIam.listRolesPaginator()).thenReturn(rolesPaginator);
            when(rolesPaginator.roles()).thenReturn(() -> List.<software.amazon.awssdk.services.iam.model.Role>of().iterator());

            // No MFA devices
            when(mockIam.listMFADevices(any(ListMfaDevicesRequest.class)))
                    .thenReturn(ListMfaDevicesResponse.builder().mfaDevices(List.of()).build());

            // No access keys
            when(mockIam.listAccessKeys(any(ListAccessKeysRequest.class)))
                    .thenReturn(ListAccessKeysResponse.builder().accessKeyMetadata(List.of()).build());

            var scanner = new IamScanner(engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("IAM User", dto.getResourceType());
            assertEquals("AIDA12345", dto.getResourceId());
            assertEquals("dev-user", dto.getResourceName());
            assertEquals("review", dto.getState());
            assertEquals(0.0, dto.getMonthlyCostUsd());
            assertEquals("Enable MFA - User Lacks Multi-Factor Authentication", dto.getRecommendation());
        }
    }

    @Test
    void scan_inactiveUser_returnsUnused() {
        try (var factory = mockStatic(ReadOnlyAwsClientFactory.class)) {
            var mockIam = mock(IamClient.class);
            factory.when(() -> ReadOnlyAwsClientFactory.build(any(), any(), any()))
                    .thenReturn(mockIam);

            // User who hasn't logged in for 120 days
            User user = User.builder()
                    .userId("AIDA99999")
                    .userName("stale-user")
                    .passwordLastUsed(Instant.now().minus(120, ChronoUnit.DAYS))
                    .createDate(Instant.now().minus(500, ChronoUnit.DAYS))
                    .build();

            var usersPaginator = mock(ListUsersIterable.class);
            when(mockIam.listUsersPaginator()).thenReturn(usersPaginator);
            when(usersPaginator.users()).thenReturn(() -> List.of(user).iterator());

            var rolesPaginator = mock(ListRolesIterable.class);
            when(mockIam.listRolesPaginator()).thenReturn(rolesPaginator);
            when(rolesPaginator.roles()).thenReturn(() -> List.<software.amazon.awssdk.services.iam.model.Role>of().iterator());

            // Has MFA (so MFA check passes -- but inactivity takes priority)
            when(mockIam.listMFADevices(any(ListMfaDevicesRequest.class)))
                    .thenReturn(ListMfaDevicesResponse.builder()
                            .mfaDevices(MFADevice.builder().serialNumber("arn:aws:iam::mfa/stale-user").build())
                            .build());

            // No access keys
            when(mockIam.listAccessKeys(any(ListAccessKeysRequest.class)))
                    .thenReturn(ListAccessKeysResponse.builder().accessKeyMetadata(List.of()).build());

            var scanner = new IamScanner(engine);
            List<ResourceDto> results = scanner.scan(null, "us-east-1");

            assertEquals(1, results.size());
            ResourceDto dto = results.getFirst();
            assertEquals("IAM User", dto.getResourceType());
            assertEquals("stale-user", dto.getResourceName());
            assertEquals("Unused - Inactive > 90 Days", dto.getRecommendation());
        }
    }
}
