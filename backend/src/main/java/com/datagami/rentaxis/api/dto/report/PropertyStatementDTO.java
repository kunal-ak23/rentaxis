package com.datagami.rentaxis.api.dto.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The property statement pack for one property and period (finance-ops spec §1).
 *
 * <p>Sections are generic on purpose — figures, tables and notes, each named by a
 * key the web, the PDF and the CSV translate — so a later owner layer can add its
 * own sections (management fee, payouts) without a new DTO.</p>
 */
public record PropertyStatementDTO(
        UUID propertyId,
        String propertyName,
        String propertyNameAr,
        String emirate,
        LocalDate from,
        LocalDate to,
        List<Section> sections,
        Footer footer) {

    /**
     * source: LEDGER (ties to the GL), REGISTER (the cheque register's operational
     * figures), MIXED, SUBLEDGER or DERIVED.
     */
    public record Section(String key, int number, String source, List<Figure> figures, List<Table> tables,
                          List<String> notes, java.util.Map<String, String> meta) {
        public Section(String key, int number, String source, List<Figure> figures, List<Table> tables, List<String> notes) {
            this(key, number, source, figures, tables, notes, java.util.Map.of());
        }
    }

    /** A headline amount; count is set where the figure is also a number of rows. */
    public record Figure(String key, BigDecimal amount, Long count) {
        public static Figure of(String key, BigDecimal amount) { return new Figure(key, amount, null); }
    }

    /** Cells are strings, numbers or ISO dates; a column key ending in the amount columns is numeric. */
    public record Table(String key, List<String> columns, List<List<Object>> rows) { }

    /** isFinal: the books are locked through {@code to}; otherwise the figures are provisional. */
    public record Footer(boolean isFinal, LocalDate booksLockedThrough, Instant generatedAt, String generatedBy) { }
}
