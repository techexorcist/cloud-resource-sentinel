package com.cloudsentinel.service;

import com.cloudsentinel.dto.ResourceDto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsvExportServiceTest {

    private final CsvExportService service = new CsvExportService();

    @Test
    void exportToCsv_formulaInjection_equalsPrefix() {
        ResourceDto r = makeResource("=cmd|\"/c calc\"!A1");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        // Dangerous prefix should be escaped with a leading single quote
        assertTrue(content.contains("'=cmd"), "= prefix should be escaped with '");
        assertFalse(content.contains(",=cmd"), "Raw = should not appear unescaped after a delimiter");
    }

    @Test
    void exportToCsv_formulaInjection_plusPrefix() {
        ResourceDto r = makeResource("+cmd|\"/c calc\"!A1");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("'+cmd"), "+ prefix should be escaped with '");
    }

    @Test
    void exportToCsv_formulaInjection_minusPrefix() {
        ResourceDto r = makeResource("-1+1");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("'-1+1"), "- prefix should be escaped with '");
    }

    @Test
    void exportToCsv_formulaInjection_atPrefix() {
        ResourceDto r = makeResource("@SUM(A1:A10)");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("'@SUM"), "@ prefix should be escaped with '");
    }

    @Test
    void exportToCsv_safeValuesNotEscaped() {
        ResourceDto r = makeResource("my-normal-resource");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        assertTrue(content.contains("my-normal-resource"));
        assertFalse(content.contains("'my-normal"), "Safe values should not be prefixed");
    }

    @Test
    void exportToCsv_includesHeaders() {
        ResourceDto r = makeResource("test");
        byte[] csv = service.exportToCsv(List.of(r));
        String content = new String(csv, StandardCharsets.UTF_8);
        String firstLine = content.split("\n")[0];
        assertTrue(firstLine.contains("region"));
        assertTrue(firstLine.contains("resource_type"));
        assertTrue(firstLine.contains("finding_type"));
        assertTrue(firstLine.contains("severity"));
        assertTrue(firstLine.contains("recommendation_detail"));
    }

    private ResourceDto makeResource(String name) {
        ResourceDto r = new ResourceDto();
        r.setRegion("us-east-1");
        r.setResourceType("EC2");
        r.setResourceId("i-12345");
        r.setResourceName(name);
        r.setState("running");
        r.setRecommendation("Active");
        r.setMonthlyCostUsd(10.0);
        return r;
    }
}
