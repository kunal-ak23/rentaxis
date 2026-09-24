package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.report.PnlLinesDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
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

    public PropertyReportController(PropertyPnlService pnl, PropertyStatementService statements,
                                    PropertyStatementPdfRenderer pdf, UserRepository users) {
        this.pnl = pnl;
        this.statements = statements;
        this.pdf = pdf;
        this.users = users;
    }

    public record ReportLineOption(String key, String labelEn, String labelAr) { }

    /** The keys the Chart of Accounts "Report line" picker offers. */
    @GetMapping("/report-lines")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public List<ReportLineOption> reportLines() {
        return ReportLines.known().stream()
                .map(k -> new ReportLineOption(k, ReportLines.labelEn(k), ReportLines.labelAr(k))).toList();
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

    /** The lines behind one P&L cell; column is a property id, UNASSIGNED or TOTAL. */
    @GetMapping("/property-pl/lines")
    public PnlLinesDTO propertyPlLines(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam String column,
            @RequestParam List<UUID> accountIds) {
        return pnl.lines(from, to, column, accountIds);
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
