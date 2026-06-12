package com.cloudsentinel.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScanReportDtoTest {

    @Test
    void isStale_withNullScannedAt_returnsFalse() {
        // Actual implementation: scannedAt != null && ... so null scannedAt returns false
        ScanReportDto dto = new ScanReportDto();
        dto.setScannedAt(null);
        assertFalse(dto.isStale());
    }

    @Test
    void isStale_with25HoursAgo_returnsTrue() {
        ScanReportDto dto = new ScanReportDto();
        dto.setScannedAt(Instant.now().minus(25, ChronoUnit.HOURS));
        assertTrue(dto.isStale());
    }

    @Test
    void isStale_with23HoursAgo_returnsFalse() {
        ScanReportDto dto = new ScanReportDto();
        dto.setScannedAt(Instant.now().minus(23, ChronoUnit.HOURS));
        assertFalse(dto.isStale());
    }

    @Test
    void isStale_withJustNow_returnsFalse() {
        ScanReportDto dto = new ScanReportDto();
        dto.setScannedAt(Instant.now());
        assertFalse(dto.isStale());
    }

    @Test
    void isStale_withExactly24HoursAgo_returnsTrue() {
        ScanReportDto dto = new ScanReportDto();
        // 24 hours = 86400 seconds, use a bit more to be safely past the boundary
        dto.setScannedAt(Instant.now().minusSeconds(86401));
        assertTrue(dto.isStale());
    }

    @Test
    void diffSummary_recordConstruction() {
        List<String> added = List.of("res-1", "res-2");
        List<String> removed = List.of("res-3");
        ScanReportDto.DiffSummary diff = new ScanReportDto.DiffSummary(
                2, 1, 15.50, -1, added, removed, "2 new resource(s) detected"
        );
        assertEquals(2, diff.resourcesAdded());
        assertEquals(1, diff.resourcesRemoved());
        assertEquals(15.50, diff.costChange());
        assertEquals(-1, diff.idleCountChange());
        assertEquals(added, diff.newResources());
        assertEquals(removed, diff.removedResources());
        assertEquals("2 new resource(s) detected", diff.narrative());
    }

    @Test
    void gettersAndSetters_work() {
        ScanReportDto dto = new ScanReportDto();
        dto.setAccountId("123456789012");
        dto.setProfileName("prod");
        dto.setPersisted(false);

        assertEquals("123456789012", dto.getAccountId());
        assertEquals("prod", dto.getProfileName());
        assertFalse(dto.isPersisted());
    }
}
