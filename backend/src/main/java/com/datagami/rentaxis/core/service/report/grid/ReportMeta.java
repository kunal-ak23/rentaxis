package com.datagami.rentaxis.core.service.report.grid;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * What a report's title band says: its key and title, the organisation, the filters in force,
 * the period ({@code from}–{@code to}) or the {@code asOf} date, and who generated it when.
 * {@code filters} defaults to none and {@code lang} is normalised to {@code en} or {@code ar}.
 */
public record ReportMeta(String reportKey, String titleKey, String orgName, List<ReportFilter> filters,
                         LocalDate from, LocalDate to, LocalDate asOf, ZonedDateTime generatedAt, String generatedBy,
                         String lang) {

    public ReportMeta {
        Objects.requireNonNull(reportKey, "reportKey");
        Objects.requireNonNull(titleKey, "titleKey");
        Objects.requireNonNull(generatedAt, "generatedAt");
        filters = filters == null ? List.of() : List.copyOf(filters);
        lang = ReportLabels.lang(lang);
    }
}
