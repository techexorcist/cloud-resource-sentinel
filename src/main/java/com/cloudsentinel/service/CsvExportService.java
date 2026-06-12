package com.cloudsentinel.service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import com.cloudsentinel.dto.ResourceDto;

/**
 * Exports scanned resource data to CSV format for download or further analysis in spreadsheet tools.
 *
 * <p>The CSV output includes dynamic columns based on the data: AI analysis columns
 * ({@code ai_confidence}, {@code ai_reasoning}) are included only if at least one resource has
 * AI analysis data, and the {@code covered_by} column is included only if at least one resource
 * has reservation coverage.</p>
 *
 * <p>All string values are sanitized via {@link #safe(String)} to prevent CSV formula injection
 * attacks (CWE-1236), where malicious cell content starting with {@code =}, {@code +}, {@code -},
 * {@code @}, {@code \t}, or {@code \r} could execute arbitrary formulas when opened in
 * spreadsheet applications like Excel or Google Sheets.</p>
 */
@Service
public class CsvExportService {

    /**
     * Exports the given list of resources to CSV format as a UTF-8 encoded byte array.
     *
     * <p>The output uses the standard CSV format from Apache Commons CSV with a header row.
     * Column inclusion is dynamic based on data presence (see class-level documentation).</p>
     *
     * @param resources the list of resources to export
     * @return the CSV content as a byte array
     * @throws RuntimeException if CSV generation fails
     */
    public byte[] exportToCsv(List<ResourceDto> resources) {
        boolean hasAiAnalysis = resources.stream().anyMatch(r -> r.getAiAnalysis() != null);
        boolean hasCoveredBy = resources.stream().anyMatch(r -> r.getCoveredBy() != null);

        List<String> headers = new ArrayList<>(List.of(
                "region", "resource_type", "resource_id", "resource_name",
                "state", "finding_type", "severity", "monthly_cost_usd", "cpu_utilization_avg", "recommendation", "recommendation_detail"
        ));
        if (hasAiAnalysis) {
            headers.add("ai_confidence");
            headers.add("ai_reasoning");
        }
        if (hasCoveredBy) {
            headers.add("covered_by");
        }
        headers.add("created_date");

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(headers.toArray(String[]::new))
                     .build())) {

            for (ResourceDto r : resources) {
                List<Object> record = new ArrayList<>(List.of(
                        safe(r.getRegion()),
                        safe(r.getResourceType()),
                        safe(r.getResourceId()),
                        safe(r.getResourceName()),
                        safe(r.getState()),
                        r.getFindingType() != null ? r.getFindingType().name() : "COST",
                        r.getSeverity() != null ? r.getSeverity().name() : "INFO",
                        r.getMonthlyCostUsd(),
                        r.getCpuUtilizationAvg(),
                        safe(r.getRecommendation()),
                        safe(r.getRecommendationDetail())
                ));
                if (hasAiAnalysis) {
                    if (r.getAiAnalysis() != null) {
                        record.add(r.getAiAnalysis().aiConfidence() + "%");
                        record.add(safe(r.getAiAnalysis().aiReasoning()));
                    } else {
                        record.add("");
                        record.add("");
                    }
                }
                if (hasCoveredBy) {
                    record.add(safe(r.getCoveredBy()));
                }
                record.add(safe(r.getCreatedDate()));
                printer.printRecord(record);
            }

            printer.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to export CSV", e);
        }
    }

    /**
     * Sanitizes a string value for safe inclusion in a CSV cell.
     *
     * <p>Prevents CSV formula injection (CWE-1236) by prefixing a single quote ({@code '})
     * to values that start with any of the following dangerous characters:</p>
     * <ul>
     *   <li>{@code =} — formula prefix in Excel/Sheets</li>
     *   <li>{@code +} — formula prefix</li>
     *   <li>{@code -} — formula prefix</li>
     *   <li>{@code @} — function call prefix in some spreadsheets</li>
     *   <li>{@code \t} — tab character, can break cell alignment</li>
     *   <li>{@code \r} — carriage return, can inject content into adjacent cells</li>
     * </ul>
     *
     * <p>The single-quote prefix causes spreadsheet applications to treat the cell content
     * as a text literal rather than a formula.</p>
     *
     * @param value the raw string value, or {@code null}
     * @return the sanitized string, or an empty string if the input was {@code null}
     */
    private String safe(String value) {
        if (value == null) return "";
        // Prevent CSV formula injection (CWE-1236)
        if (!value.isEmpty()) {
            char first = value.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                return "'" + value;
            }
        }
        return value;
    }
}
