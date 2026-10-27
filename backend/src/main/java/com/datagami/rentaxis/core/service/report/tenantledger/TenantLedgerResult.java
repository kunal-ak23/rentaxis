package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.core.service.report.grid.GroupPager;
import com.datagami.rentaxis.core.service.report.grid.GroupTotals;
import com.datagami.rentaxis.core.service.report.grid.ReportMeta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One run of the Tenant Ledger, read inside the service's transaction and rendered after it: immutable, every name
 * already in the report language.
 *
 * <p>{@code accounts} carries every group of the report with its authoritative (SQL) figures, whichever page this
 * is; {@code lines} only the rows of this page (or of the whole report for an export). {@code span} is the page's
 * place in the report, {@code null} for an export, which also has {@code pageRows} 0. {@code totalRows} and
 * {@code totalGroups} cover the whole report.</p>
 */
public record TenantLedgerResult(ReportMeta meta, List<AccountGroup> accounts, List<Line> lines, GroupPager.PageSpan span,
                                 int page, int pageCount, int pageRows, long totalRows, int totalGroups) {

    public TenantLedgerResult {
        Objects.requireNonNull(meta, "meta");
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** One ledger account: its band label, the label of its Sub Total, and its tenants in report order. */
    public record AccountGroup(UUID accountId, String code, String label, String subTotalLabel, List<TenantGroup> tenants) {

        public AccountGroup {
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
        }

        /** Σ of the tenants' totals. */
        public GroupTotals totals() {
            return tenants.stream().map(TenantGroup::totals).reduce(zero(), GroupTotals::plus);
        }
    }

    /**
     * One (account, renter) group. {@code ordinal} is its 0-based place among all the report's groups (the pager's
     * group index); {@code renterId} {@code null} is "No tenant"; {@code opening} is the balance before {@code from};
     * {@code debit}, {@code credit} and {@code rowCount} cover the window.
     */
    public record TenantGroup(int ordinal, UUID renterId, String label, BigDecimal opening, BigDecimal debit,
                              BigDecimal credit, int rowCount) {

        /** Debit and credit sums, and the closing {@code opening + debit − credit}. */
        public GroupTotals totals() {
            return GroupTotals.debitCredit(opening, debit, credit);
        }
    }

    /**
     * One printed row. {@code rn} is its 1-based place in its group and {@code movement} the group's running
     * Σ(debit − credit) inside the window through this row. A zero {@code debit} or {@code credit} is {@code null}
     * (a blank cell, as PACT prints it).
     */
    public record Line(UUID accountId, UUID renterId, int rn, BigDecimal movement, UUID entryId, String entryNumber,
                       String docType, LocalDate entryDate, String particular, BigDecimal debit, BigDecimal credit,
                       String chqNo, String payeeBank, Integer bdDays, LocalDate convertedDate, UUID leaseId,
                       UUID chequeId) {
    }

    /** Σ of the accounts' totals: the REPORT TOTAL. */
    public GroupTotals reportTotals() {
        return accounts.stream().map(AccountGroup::totals).reduce(zero(), GroupTotals::plus);
    }

    private static GroupTotals zero() {
        return GroupTotals.debitCredit(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
