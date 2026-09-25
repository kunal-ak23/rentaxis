package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.report.BalanceSheetDTO;
import com.datagami.rentaxis.api.dto.report.PnlLinesDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.core.service.report.BalanceSheetService;
import com.datagami.rentaxis.core.service.report.FinancialReportExport;
import com.datagami.rentaxis.core.service.report.PnlAllocation;
import com.datagami.rentaxis.core.service.report.PnlPeriods;
import com.datagami.rentaxis.core.service.report.PropertyPnlService;
import com.datagami.rentaxis.core.service.report.ReportLines;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementPdfRenderer;
import com.datagami.rentaxis.core.service.report.statement.PropertyStatementService;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Finance → Reports: the per-property P&L and the property statement pack
 * (finance-ops spec §1). Read-only over the ledger.
 *
 * <p>{@code /property-pl} is the prod-readiness spec's {@code /finance/profit-and-loss}
 * renamed; the year-end-close PR reuses it for its close preview.</p>
 *
 * <p>PROPERTY_MANAGER is admitted read-only and narrowed to assigned properties by
 * the services ({@code PropertyScope}); a named foreign property is a 404, and the
 * tenant-wide Unassigned / Total / allocation / check figures are left out of what
 * a manager receives.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER')")
public class PropertyReportController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv; charset=UTF-8");

    private final PropertyPnlService pnl;
    private final PropertyStatementService statements;
    private final PropertyStatementPdfRenderer pdf;
    private final UserRepository users;
    private final BalanceSheetService balanceSheet;

    public PropertyReportController(PropertyPnlService pnl, PropertyStatementService statements,
                                    PropertyStatementPdfRenderer pdf, UserRepository users,
                                    BalanceSheetService balanceSheet) {
        this.pnl = pnl;
        this.statements = statements;
        this.pdf = pdf;
        this.users = users;
        this.balanceSheet = balanceSheet;
    }

    /** accountType: the only account type the line may be set on. */
    public record ReportLineOption(String key, String labelEn, String labelAr, String accountType) { }

    /** The keys the Chart of Accounts "Report line" picker offers. */
    @GetMapping("/report-lines")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public List<ReportLineOption> reportLines() {
        return ReportLines.known().stream()
                .map(k -> new ReportLineOption(k, ReportLines.labelEn(k), ReportLines.labelAr(k),
                        ReportLines.naturalType(k).name())).toList();
    }

    @GetMapping("/property-pl")
    public PropertyPnlDTO propertyPl(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> propertyId,
            @RequestParam(defaultValue = "NONE") PnlPeriods.Compare compare,
            @RequestParam(defaultValue = "NONE") PnlAllocation.Basis allocate) {
        return pnl.pnl(from, to, propertyId, compare, allocate);
    }

    @GetMapping("/property-pl.csv")
    public ResponseEntity<byte[]> propertyPlCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> propertyId,
            @RequestParam(defaultValue = "NONE") PnlPeriods.Compare compare,
            @RequestParam(defaultValue = "NONE") PnlAllocation.Basis allocate,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = ReportCsv.pnl(pnl.pnl(from, to, propertyId, compare, allocate), lang);
        return file(body, CSV, "property-pl-" + from + "-" + to + ".csv");
    }

    @GetMapping("/property-pl.pdf")
    public ResponseEntity<byte[]> propertyPlPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> propertyId,
            @RequestParam(defaultValue = "NONE") PnlPeriods.Compare compare,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = FinancialReportExport.pnlPdf(pnl.pnl(from, to, propertyId, compare, PnlAllocation.Basis.NONE), lang, false);
        return file(body, MediaType.APPLICATION_PDF, "property-pl-" + from + "-" + to + "-" + lang(lang) + ".pdf");
    }

    // ------------------------------------------------------------------ F14-10: company P&L

    /** The company P&L is the property P&L's Total column: tenant-wide, so not for a property manager. */
    @GetMapping("/company-pl")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public PropertyPnlDTO companyPl(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PREVIOUS") PnlPeriods.Compare compare) {
        return pnl.pnl(from, to, null, compare, PnlAllocation.Basis.NONE);
    }

    @GetMapping("/company-pl.pdf")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<byte[]> companyPlPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PREVIOUS") PnlPeriods.Compare compare,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = FinancialReportExport.pnlPdf(companyPl(from, to, compare), lang, true);
        return file(body, MediaType.APPLICATION_PDF, "company-pl-" + from + "-" + to + "-" + lang(lang) + ".pdf");
    }

    @GetMapping("/company-pl.csv")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<byte[]> companyPlCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "PREVIOUS") PnlPeriods.Compare compare,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = FinancialReportExport.companyPnlCsv(companyPl(from, to, compare), lang);
        return file(body, CSV, "company-pl-" + from + "-" + to + ".csv");
    }

    // ------------------------------------------------------------------ F14-10: balance sheet

    @GetMapping("/balance-sheet")
    public BalanceSheetDTO balanceSheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareAt,
            @RequestParam(required = false) List<UUID> propertyId) {
        return balanceSheet.balanceSheet(asAt, compareAt, propertyId);
    }

    @GetMapping("/balance-sheet.pdf")
    public ResponseEntity<byte[]> balanceSheetPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareAt,
            @RequestParam(required = false) List<UUID> propertyId,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = FinancialReportExport.balanceSheetPdf(balanceSheet.balanceSheet(asAt, compareAt, propertyId), lang);
        return file(body, MediaType.APPLICATION_PDF, "balance-sheet-" + asAt + "-" + lang(lang) + ".pdf");
    }

    @GetMapping("/balance-sheet.csv")
    public ResponseEntity<byte[]> balanceSheetCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asAt,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareAt,
            @RequestParam(required = false) List<UUID> propertyId,
            @RequestParam(defaultValue = "en") String lang) {
        byte[] body = FinancialReportExport.balanceSheetCsv(balanceSheet.balanceSheet(asAt, compareAt, propertyId), lang);
        return file(body, CSV, "balance-sheet-" + asAt + ".csv");
    }

    /**
     * The figure a drill-down asks for, by key: a row ({@code rowKey}), a group
     * subtotal ({@code groupId}) or NOI (neither), in one column (a property id,
     * UNASSIGNED or TOTAL), over the report's selection ({@code propertyIds}).
     */
    public record LinesRequest(LocalDate from, LocalDate to, String column, String rowKey, UUID groupId,
                               List<UUID> propertyIds) { }

    /**
     * The lines behind one P&L figure. POST with the figure named by key rather
     * than a GET listing every leaf: a NOI over hundreds of leaves would overrun
     * the request-line limit. Read-only all the same.
     */
    @PostMapping("/property-pl/lines")
    public PnlLinesDTO propertyPlLines(@RequestBody LinesRequest r) {
        return pnl.lines(r.from(), r.to(), r.column(), r.rowKey(), r.groupId(), r.propertyIds());
    }

    @GetMapping("/property-statement")
    public PropertyStatementDTO propertyStatement(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statements.statement(propertyId, from, to, callerName());
    }

    @GetMapping("/property-statement.pdf")
    public ResponseEntity<byte[]> propertyStatementPdf(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "en") String lang) {
        PropertyStatementDTO s = statements.statement(propertyId, from, to, callerName());
        return file(pdf.render(s, lang), MediaType.APPLICATION_PDF, "property-statement-" + from + "-" + to + "-" + lang(lang) + ".pdf");
    }

    @GetMapping("/property-statement.csv")
    public ResponseEntity<byte[]> propertyStatementCsv(
            @RequestParam UUID propertyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "en") String lang) {
        PropertyStatementDTO s = statements.statement(propertyId, from, to, callerName());
        return file(ReportCsv.statement(s, lang), CSV, "property-statement-" + from + "-" + to + ".csv");
    }

    private static String lang(String lang) {
        return "ar".equals(lang) ? "ar" : "en";
    }

    /** The name the pack's footer prints; the pack is still produced when the caller cannot be named. */
    private String callerName() {
        try {
            return users.findById(CallerIdentity.callerId())
                    .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ResponseEntity<byte[]> file(byte[] body, MediaType type, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(type)
                .body(body);
    }
}
