package com.datagami.rentaxis.core.service.report.grid;

/**
 * One filter shown in a report's title band: {@code labelKey} is a key of the reports bundle
 * (for example {@code filter.property}) and {@code value} the already-resolved name.
 */
public record ReportFilter(String labelKey, String value) {
}
