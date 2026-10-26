package com.datagami.rentaxis.core.service.report.grid;

import java.util.List;

/**
 * A report's header as every sink receives it, already in the report language: the report key,
 * title, organisation, the period and filter lines, the "Generated … by …" line, and the columns
 * with their labels ({@code columnLabels.get(i)} belongs to {@code columns.get(i)}, hidden ones included).
 */
public record GridHeader(String reportKey, String title, String orgName, List<String> metaLines, String generatedLine,
                         List<ReportColumn> columns, List<String> columnLabels, String lang) {

    public GridHeader {
        metaLines = List.copyOf(metaLines);
        columns = List.copyOf(columns);
        columnLabels = List.copyOf(columnLabels);
        if (columns.size() != columnLabels.size()) {
            throw new IllegalArgumentException("Every column needs exactly one label");
        }
    }
}
