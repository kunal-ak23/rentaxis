package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.api.dto.report.ReportGridDTO;
import com.datagami.rentaxis.core.service.report.grid.ExportFormat;
import com.datagami.rentaxis.core.service.report.grid.ReportCaps;
import com.datagami.rentaxis.core.service.report.grid.ReportFiles;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerFilter;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerGrid;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerReportService;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Finance → Reports, the PACT registers (reports spec §1.4, §2): each report has a JSON endpoint for one screen
 * page and exactly one {@code /<key>.{format}} handler for its xlsx, pdf and csv files. Read-only over the ledger.
 *
 * <p>Finance roles only (D6), with an explicit {@code @PreAuthorize} on every method: no generic {@code {key}}
 * dispatcher, which would hide a role check from review and from {@code PropertyScopeCoverageTest}. Organisation
 * required ({@code OrganisationRequiredInterceptor}): a SUPER_ADMIN with none selected is told to pick one.</p>
 *
 * <p>The services read inside their own read-only transaction into immutable records; the grid is rendered here,
 * after it. A file over its format's row cap is a 422 ({@code report.tooManyRows*}) raised before any line is
 * read.</p>
 */
@RestController
@RequestMapping("/api/v1/finance/reports")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class FinanceRegisterReportController {

    private final TenantLedgerReportService tenantLedger;
    private final ReportLabels labels;
    private final ReportCaps caps;
    private final UserRepository users;

    public FinanceRegisterReportController(TenantLedgerReportService tenantLedger, ReportLabels labels, ReportCaps caps,
                                           UserRepository users) {
        this.tenantLedger = tenantLedger;
        this.labels = labels;
        this.caps = caps;
        this.users = users;
    }

    // ------------------------------------------------------------------ R1 Tenant Ledger

    /** One screen page (at most {@link ReportCaps#screenPageRows()} rows) of the Tenant Ledger; {@code page} is 0-based. */
    @GetMapping("/tenant-ledger")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ReportGridDTO tenantLedger(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) List<UUID> accountIds,
            @RequestParam(required = false) UUID renterId,
            @RequestParam(required = false) UUID leaseId,
            @RequestParam(defaultValue = "false") boolean onlyWithBalance,
            @RequestParam(defaultValue = "false") boolean hideReversed,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "en") String lang) {
        TenantLedgerFilter filter = new TenantLedgerFilter(from, to, propertyId, accountIds, renterId, leaseId,
                onlyWithBalance, hideReversed, lang);
        TenantLedgerResult r = tenantLedger.page(filter, page, caps.screenPageRows(), callerName());
        return TenantLedgerGrid.json(r, labels);
    }

    /** The whole Tenant Ledger as an xlsx (PACT layout), pdf or csv file. */
    @GetMapping("/tenant-ledger.{format:xlsx|pdf|csv}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<byte[]> tenantLedgerFile(
            @PathVariable String format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) List<UUID> accountIds,
            @RequestParam(required = false) UUID renterId,
            @RequestParam(required = false) UUID leaseId,
            @RequestParam(defaultValue = "false") boolean onlyWithBalance,
            @RequestParam(defaultValue = "false") boolean hideReversed,
            @RequestParam(defaultValue = "en") String lang) {
        ExportFormat f = ExportFormat.of(format);
        TenantLedgerFilter filter = new TenantLedgerFilter(from, to, propertyId, accountIds, renterId, leaseId,
                onlyWithBalance, hideReversed, lang);
        TenantLedgerResult r = tenantLedger.export(filter, f, callerName());
        return ReportFiles.response(TenantLedgerGrid.file(r, f, labels, caps.maxRows(f)), f,
                TenantLedgerGrid.filename(r, f));
    }

    /** The receivable leaves the Tenant Ledger's account picker offers: one property's, or every property's. */
    @GetMapping("/tenant-ledger/accounts")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public List<ReceivableAccountDTO> tenantLedgerAccounts(@RequestParam(required = false) UUID propertyId) {
        return tenantLedger.receivableAccounts(propertyId);
    }

    // ------------------------------------------------------------------

    /**
     * The name the title band's "Generated … by …" line prints (as {@code PropertyReportController}); the report
     * is still produced when the caller cannot be named.
     */
    private String callerName() {
        try {
            return users.findById(CallerIdentity.callerId())
                    .map(u -> u.getName() != null && !u.getName().isBlank() ? u.getName() : u.getEmail())
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
