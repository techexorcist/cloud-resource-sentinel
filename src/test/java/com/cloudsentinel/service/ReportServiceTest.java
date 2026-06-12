package com.cloudsentinel.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        var env = new org.springframework.mock.env.MockEnvironment();
        reportService = new ReportService(env);
    }

    @Test
    void resolveAccountId_withProfileContaining12DigitSuffix() {
        assertEquals("123456789012", reportService.resolveAccountId("PowerUser-123456789012"));
    }

    @Test
    void resolveAccountId_withSimpleProfile_returnsProfileName() {
        assertEquals("prod", reportService.resolveAccountId("prod"));
    }

    @Test
    void resolveAccountId_withHyphenatedProfileNoDigitSuffix() {
        assertEquals("my-profile", reportService.resolveAccountId("my-profile"));
    }

    @Test
    void resolveAccountId_withNull_returnsDefault() {
        assertEquals("default", reportService.resolveAccountId(null));
    }

    @Test
    void resolveAccountId_withBlank_returnsDefault() {
        assertEquals("default", reportService.resolveAccountId(""));
        assertEquals("default", reportService.resolveAccountId("   "));
    }

    @Test
    void resolveAccountId_withExtraSuffixNotTwelveDigits_returnsFullName() {
        // "extra" is the last part, not 12 digits
        assertEquals("PowerUser-123456789012-extra", reportService.resolveAccountId("PowerUser-123456789012-extra"));
    }

    @Test
    void resolveAccountId_bareTwelveDigitNumber_returnsAsIs() {
        // No hyphens, so parts.length is 1, which is < 2
        // Falls through to return profileName
        assertEquals("123456789012", reportService.resolveAccountId("123456789012"));
    }

    @Test
    void resolveAccountId_multipleHyphensWithTwelveDigitEnd() {
        assertEquals("123456789012", reportService.resolveAccountId("my-long-profile-name-123456789012"));
    }

    @Test
    void resolveAccountId_lastPartElevenDigits_returnsFullName() {
        assertEquals("prof-12345678901", reportService.resolveAccountId("prof-12345678901"));
    }

    @Test
    void resolveAccountId_lastPartThirteenDigits_returnsFullName() {
        assertEquals("prof-1234567890123", reportService.resolveAccountId("prof-1234567890123"));
    }
}
