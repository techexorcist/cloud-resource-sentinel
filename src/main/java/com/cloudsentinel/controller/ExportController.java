package com.cloudsentinel.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.cloudsentinel.dto.ResourceDto;
import com.cloudsentinel.service.CsvExportService;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * REST controller for the Export API group, providing data export functionality for scan results.
 *
 * <p>Currently supports CSV export of resource lists. The exported file contains all resource
 * fields (ID, type, name, region, state, cost, recommendation, etc.) formatted for spreadsheet
 * consumption.</p>
 */
@RestController
@Tag(name = "Export")
public class ExportController {

    /** Maximum number of resources that can be exported in a single CSV request. */
    private static final int MAX_EXPORT_RESOURCES = 10_000;

    private final CsvExportService csvExportService;
    private final ObjectMapper objectMapper;

    /**
     * Constructs the export controller with its required service dependencies.
     *
     * @param csvExportService the CSV export service for generating CSV byte arrays
     * @param objectMapper     the Jackson ObjectMapper for deserializing resource DTOs from raw maps
     */
    public ExportController(CsvExportService csvExportService, ObjectMapper objectMapper) {
        this.csvExportService = csvExportService;
        this.objectMapper = objectMapper;
    }

    /**
     * Exports a list of resources to a downloadable CSV file.
     *
     * <p>POST {@code /export/csv}</p>
     *
     * <p>The request body must contain a {@code "resources"} key with a list of resource objects.
     * Each resource object is deserialized into a {@link ResourceDto} using Jackson. The response
     * is a binary CSV file with the {@code Content-Disposition} header set for download.</p>
     *
     * @param body the request body containing a {@code "resources"} list
     * @return 200 with CSV byte array and download headers, or 400 if resources are missing or empty
     */
    @Operation(summary = "Export resources to CSV", description = "Exports a list of resources to a downloadable CSV file with all fields.")
    @PostMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestBody Map<String, Object> body) {
        Object rawResources = body.get("resources");
        if (rawResources == null) {
            return ResponseEntity.badRequest().build();
        }

        @SuppressWarnings("unchecked")
        List<?> rawList = (List<?>) rawResources;
        if (rawList.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (rawList.size() > MAX_EXPORT_RESOURCES) {
            return ResponseEntity.badRequest()
                    .body(("Export limited to " + MAX_EXPORT_RESOURCES + " resources").getBytes());
        }

        List<ResourceDto> resources = rawList.stream()
                .map(item -> objectMapper.convertValue(item, ResourceDto.class))
                .toList();

        byte[] csv = csvExportService.exportToCsv(resources);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cloud-sentinel-report.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
