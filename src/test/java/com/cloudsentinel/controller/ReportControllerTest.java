package com.cloudsentinel.controller;

import com.cloudsentinel.dto.AnalysisResponse;
import com.cloudsentinel.dto.ScanReportDto;
import com.cloudsentinel.service.ReportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock
    private ReportService reportService;

    @InjectMocks
    private ReportController controller;

    @Test
    void getCachedReport_returns204_whenNoReport() {
        when(reportService.resolveAccountId(anyString())).thenReturn("123456789012");
        when(reportService.getLatestReport("123456789012")).thenReturn(Optional.empty());

        var response = controller.getCachedReport("test");
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void getCachedReport_returns200_whenReportExists() {
        var report = new ScanReportDto();
        report.setAccountId("123456789012");
        report.setAnalysisResponse(new AnalysisResponse());
        report.setScannedAt(Instant.now());

        when(reportService.resolveAccountId(anyString())).thenReturn("123456789012");
        when(reportService.getLatestReport("123456789012")).thenReturn(Optional.of(report));

        var response = controller.getCachedReport("test");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("123456789012", response.getBody().getAccountId());
    }

    @Test
    void getReports_returnsList() {
        when(reportService.resolveAccountId(anyString())).thenReturn("123");
        when(reportService.getReportsForAccount("123")).thenReturn(List.of());

        var result = controller.getReports("test");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void cacheCount_returnsExpectedShape() {
        when(reportService.getReportCountsByAccount()).thenReturn(Map.of("123", 2));

        var result = controller.cacheCount();
        assertEquals(2, result.get("count"));
    }

    @Test
    void clearCache_returnsDeletedCount() {
        when(reportService.clearAllReports()).thenReturn(3);

        var result = controller.clearCache();
        assertEquals(3, result.get("deleted"));
    }

    @Test
    void compareReports_returns400_forNoReports() {
        when(reportService.resolveAccountId(anyString())).thenReturn("123");
        when(reportService.getReportsForAccount("123")).thenReturn(List.of());

        var response = controller.compareReports("test", 0, 1);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
