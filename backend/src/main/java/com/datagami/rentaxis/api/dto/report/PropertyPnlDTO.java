package com.datagami.rentaxis.api.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The per-property P&L (finance-ops spec §1). Every map of amounts is keyed by
 * column key: a property id, {@code UNASSIGNED} or {@code TOTAL}. For a property
 * manager ({@code scoped}) there are only property columns, no allocation and no
 * check row: those are tenant-wide figures.
 */
public record PropertyPnlDTO(
        LocalDate from,
        LocalDate to,
        String compare,
        LocalDate priorFrom,
        LocalDate priorTo,
        boolean scoped,
        List<Column> columns,
        List<Group> groups,
        Map<String, Amount> income,
        Map<String, Amount> expenses,
        Map<String, Amount> noi,
        Allocation allocation,
        Check check,
        DataQuality dataQuality) {

    public static final String UNASSIGNED = "UNASSIGNED";
    public static final String TOTAL = "TOTAL";

    /** kind: PROPERTY, UNASSIGNED or TOTAL. */
    public record Column(String key, UUID propertyId, String kind, String name, String nameAr) { }

    /** prior / delta / deltaPct are null without a comparison; deltaPct is also null when prior is 0. */
    public record Amount(BigDecimal amount, BigDecimal prior, BigDecimal delta, BigDecimal deltaPct) { }

    /** A level-2 group of the chart ("Direct Income", "Indirect Expense" on the standard seed). */
    public record Group(UUID groupId, String code, String name, String nameAr, String accountType,
                        List<Row> rows, Map<String, Amount> subtotal) { }

    /** key: the report line, or the account id when the account has none. accountIds: every leaf in the row, for the drill-down. */
    public record Row(String key, String reportLine, String label, String labelAr, List<UUID> accountIds,
                      Map<String, Amount> cells) { }

    /**
     * Report-only spread of the Unassigned column's net cost (expenses − income)
     * over the property columns. Nothing is posted. basisUsed differs from basis
     * when the basis had no weight (no units, no rent) and the spread fell back to
     * equal shares.
     */
    public record Allocation(String basis, String basisUsed, BigDecimal unassignedCost,
                             Map<String, BigDecimal> allocated, Map<String, BigDecimal> noiAfter) { }

    /** Σ every column including Unassigned against the tenant-wide ledger movement; difference is 0 unless the query is wrong. */
    public record Check(BigDecimal ledgerNet, BigDecimal reportNet, BigDecimal difference, boolean ok) { }

    /** Lines whose own property differs from their account's property: they count under the line's property. */
    public record DataQuality(long lineAccountPropertyMismatches) { }
}
