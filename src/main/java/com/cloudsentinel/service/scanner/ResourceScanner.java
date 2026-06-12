package com.cloudsentinel.service.scanner;

import java.util.List;

import com.cloudsentinel.dto.FindingType;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;

public interface ResourceScanner {

    List<com.cloudsentinel.dto.ResourceDto> scan(AwsCredentialsProvider creds, String region);

    default boolean isGlobal() {
        return false;
    }

    default String globalRegion() {
        return "us-east-1";
    }

    default ScanCategory category() {
        return ScanCategory.COST_OPTIMIZATION;
    }

    /**
     * Returns the finding type that this scanner's results should carry.
     * Cost scanners default to COST; security/governance scanners override this.
     */
    default FindingType findingType() {
        return FindingType.COST;
    }

    enum ScanCategory {
        COST_OPTIMIZATION,
        SECURITY_GOVERNANCE,
        FULL
    }
}
