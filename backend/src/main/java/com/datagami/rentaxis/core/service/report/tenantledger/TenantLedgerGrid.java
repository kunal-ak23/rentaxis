package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.dto.report.ReportGridDTO;
import com.datagami.rentaxis.core.service.report.grid.Band;
import com.datagami.rentaxis.core.service.report.grid.ExportFormat;
import com.datagami.rentaxis.core.service.report.grid.GridRenderer;
import com.datagami.rentaxis.core.service.report.grid.GroupPager;
import com.datagami.rentaxis.core.service.report.grid.JsonGridSink;
import com.datagami.rentaxis.core.service.report.grid.ReportColumn;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;
import com.datagami.rentaxis.core.service.report.grid.ReportWriter;
import com.datagami.rentaxis.core.service.report.grid.RowStyle;
import com.datagami.rentaxis.core.service.report.grid.TotalRule;
import com.datagami.rentaxis.core.service.report.grid.XlsxStyle;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.AccountGroup;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.Line;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.TenantGroup;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders a {@link TenantLedgerResult} (reports spec §2): replays its records as {@link ReportWriter} events, so
 * the screen's JSON and every file format print the writer's figures. Runs after the service's transaction.
 *
 * <p>Layout, PACT's: an ACCOUNT band per receivable account, a PARTY band "Tenant Name : …" per tenant with its
 * brought-forward row, the tenant's rows and a Sub Total; an account Sub Total when the account has more than one
 * tenant; the REPORT TOTAL. A page that starts inside a group re-opens its bands as "(continued)" and carries the
 * last balance of the earlier page forward.</p>
 */
public final class TenantLedgerGrid {

    public static final String KEY = "tenant-ledger";

    /** PACT's columns in PACT's order, then Doc No (column J). BDDays is never summed. */
    public static final List<ReportColumn> COLUMNS = List.of(
            ReportColumn.date("docDate", "col.docDate", 12, "dd-MM-yyyy"),
            ReportColumn.text("particular", "col.particular", 36),
            ReportColumn.money("debit", "col.debit", 14),
            ReportColumn.money("credit", "col.credit", 14),
            ReportColumn.balance("balance", "col.balance", 16, "debit", "credit"),
            ReportColumn.idText("chqNo", "col.chqNo", 12),
            ReportColumn.text("payeeBank", "col.payeeBank", 18),
            ReportColumn.integer("bdDays", "col.bdDays", 8, TotalRule.NONE),
            ReportColumn.date("convertedDate", "col.convertedDate", 14, "dd/MM/yyyy"),
            ReportColumn.idText("docNo", "col.docNo", 14, "journal"));

    private TenantLedgerGrid() {
    }

    /** Writes the report ({@code begin} … {@code end}); a page ({@code span} set) is fed as a partial report. */
    public static void feed(TenantLedgerResult r, ReportWriter w, ReportLabels.Bound labels) {
        GroupPager.PageSpan span = r.span();
        if (span != null) {
            w.markPartial();
        }
        w.begin(r.meta(), COLUMNS);
        Map<Group, List<Line>> linesByGroup = new LinkedHashMap<>();
        for (Line l : r.lines()) {
            linesByGroup.computeIfAbsent(new Group(l.accountId(), l.renterId()), k -> new ArrayList<>()).add(l);
        }
        String subTotal = labels.get("report.subTotal");
        for (AccountGroup a : r.accounts()) {
            List<TenantGroup> ts = a.tenants().stream()
                    .filter(t -> span == null || (t.ordinal() >= span.firstGroup() && t.ordinal() <= span.lastGroup()))
                    .toList();
            if (ts.isEmpty()) {
                continue;
            }
            TenantGroup first = ts.get(0);
            // The account began on an earlier page when an earlier tenant of it did, or this page opens mid-tenant.
            boolean accountContinues = span != null && first.ordinal() == span.firstGroup()
                    && (a.tenants().get(0).ordinal() < first.ordinal() || span.firstRow() > 1);
            if (accountContinues) {
                w.continueGroup(1, Band.ACCOUNT, a.label(), null);
            } else {
                w.openGroup(1, Band.ACCOUNT, a.label());
            }
            for (TenantGroup t : ts) {
                List<Line> ls = linesByGroup.getOrDefault(new Group(a.accountId(), t.renterId()), List.of());
                if (span != null && t.ordinal() == span.firstGroup() && span.firstRow() > 1) {
                    w.continueGroup(2, Band.PARTY, t.label(), carriedForward(t, ls));
                } else {
                    w.openGroup(2, Band.PARTY, t.label());
                    w.opening(t.opening());
                }
                for (Line l : ls) {
                    w.row(cells(l), RowStyle.PLAIN, refs(l));
                }
                boolean tenantEnds = span == null || t.ordinal() < span.lastGroup() || span.lastRow() >= t.rowCount();
                if (!tenantEnds) {
                    w.end(r.reportTotals());              // the page stops inside this tenant
                    return;
                }
                w.closeGroup(subTotal, t.totals());
            }
            boolean accountEnds = ts.get(ts.size() - 1).ordinal() == a.tenants().get(a.tenants().size() - 1).ordinal();
            if (!accountEnds) {
                w.end(r.reportTotals());                  // the page stops between two tenants of this account
                return;
            }
            w.closeGroup(a.tenants().size() > 1 ? a.subTotalLabel() : null, a.totals());
        }
        w.end(r.reportTotals());
    }

    /** The screen's grid: this page of the report, or the whole report on one unpaged page for an export. */
    public static ReportGridDTO json(TenantLedgerResult r, ReportLabels labels) {
        GroupPager.PageSpan s = r.span();
        ReportGridDTO.Page page = s == null
                ? ReportGridDTO.Page.whole(r.totalRows(), r.totalGroups())
                : new ReportGridDTO.Page(r.page(), r.pageCount(), r.pageRows(), r.totalRows(), r.totalGroups(),
                        s.firstGroup(), s.lastGroup(), false, false);
        JsonGridSink sink = new JsonGridSink(page);
        ReportLabels.Bound bound = labels.bind(r.meta().lang());
        feed(r, new ReportWriter(sink, bound, Integer.MAX_VALUE), bound);
        return sink.result();
    }

    /** The file's bytes in PACT's Tenant Ledger style; rows past {@code rowCap} are dropped and the file says so. */
    public static byte[] file(TenantLedgerResult r, ExportFormat f, ReportLabels labels, int rowCap) {
        ReportLabels.Bound bound = labels.bind(r.meta().lang());
        return GridRenderer.render(f, XlsxStyle.TENANT_LEDGER, bound, rowCap, w -> feed(r, w, bound));
    }

    /** {@code tenant-ledger-<from>-<to>.<ext>}, with {@code -ar} before the dot for an Arabic report. */
    public static String filename(TenantLedgerResult r, ExportFormat f) {
        String ar = "ar".equals(r.meta().lang()) ? "-ar" : "";
        return KEY + "-" + r.meta().from() + "-" + r.meta().to() + ar + "." + f.ext();
    }

    // ------------------------------------------------------------------

    /** The balance the earlier page ended on: the opening plus the window's movement before this page's first row. */
    private static BigDecimal carriedForward(TenantGroup t, List<Line> ls) {
        if (ls.isEmpty()) {
            return t.opening();
        }
        Line l0 = ls.get(0);
        return t.opening().add(l0.movement()).subtract(nz(l0.debit()).subtract(nz(l0.credit())));
    }

    private static Map<String, Object> cells(Line l) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("docDate", l.entryDate());
        c.put("particular", l.particular());
        c.put("debit", l.debit());
        c.put("credit", l.credit());
        c.put("chqNo", l.chqNo());
        c.put("payeeBank", l.payeeBank());
        c.put("bdDays", l.bdDays());
        c.put("convertedDate", l.convertedDate());
        c.put("docNo", l.entryNumber());
        return c;
    }

    private static Map<String, UUID> refs(Line l) {
        Map<String, UUID> refs = new LinkedHashMap<>();
        refs.put("entryId", l.entryId());
        refs.put("leaseId", l.leaseId());
        refs.put("chequeId", l.chequeId());
        refs.values().removeIf(Objects::isNull);
        return refs;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** A group's key: (account, renter), renter {@code null} for "No tenant". */
    private record Group(UUID accountId, UUID renterId) {}
}
