package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The one cut-over fixture every plan-4 step-1 test is built on: a fresh
 * organisation, the chart the template's sample rows name, a books start date of
 * 2026-10-01 with the books locked through the day before, and the real
 * downloadable template as the workbook.
 *
 * <p><b>The workbook is the template this release actually ships</b>, not a
 * fixture shaped to suit the parser — the same choice {@code ContractImportIT}
 * made and for the same reason. Its two sample contracts are what every
 * hand-derived balance in {@code CutoverReconciliationIT} is computed from, so a
 * change to the template that changes the arithmetic fails a test that states the
 * arithmetic rather than one that recomputes it.</p>
 *
 * <p><b>Why a bean and not a base class.</b> Three ITs need it and two of them
 * already extend nothing; a shared superclass would also share the Spring context
 * key, and {@code ContractImportPostIT} deliberately imports an event recorder
 * that the others must not get.</p>
 *
 * <p><b>The SecurityContext matters here.</b> Posting a lease runs through
 * {@code LeaseAccessPolicy}, which fails closed for a caller it cannot recognise —
 * and a bare JUnit thread has no authentication at all, so every post would answer
 * "Lease not found". {@link #authenticateAsTenantAdmin()} installs the same shape
 * {@code ApiSecurityFilter} installs on a request thread.</p>
 */
@Component
public class CutoverFixture {

    /** Spec §10.3's D: the day the client's books open on this system. */
    public static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);

    /** D − 1: every cut-over journal is dated on or before this, inside the locked period. */
    public static final LocalDate AS_OF = BOOKS_START.minusDays(1);

    private final LandlordOrgRepository orgs;
    private final AccountService accounts;
    private final PropertyAccountService propertyAccounts;
    private final TenantFiscalSettingsService fiscal;
    private final PortfolioTemplateService templates;
    private final ImportJobRepository importJobs;

    public CutoverFixture(LandlordOrgRepository orgs, AccountService accounts,
                          PropertyAccountService propertyAccounts, TenantFiscalSettingsService fiscal,
                          PortfolioTemplateService templates, ImportJobRepository importJobs) {
        this.orgs = orgs;
        this.accounts = accounts;
        this.propertyAccounts = propertyAccounts;
        this.fiscal = fiscal;
        this.templates = templates;
        this.importJobs = importJobs;
    }

    public UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        // A VAT-bearing contract issues tax invoices, which need the TRN (spec 2026-09-24 §1).
        org.setTrn(com.datagami.rentaxis.testsupport.LeaseTestFixtures.FIXTURE_TRN);
        return orgs.save(org).getId();
    }

    /**
     * The chart the cut-over template's sample row names, plus the default
     * template and tenant defaults (which is where OUTPUT_VAT's mapping comes
     * from — the Properties sheet has no column for it).
     */
    public void seedChart() {
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        leaf("Rental Income ST1", "C-01-01");
        leaf("Rent Receivable - ST1", "A-02-01");
        leaf("Advance Rent - ST1", "B-01-01");
        leaf("Sample Bank - ST1", "A-02-02");
        leaf("PDC Receivable ST1", "A-02-03");
        leaf("Security Deposit ST1", "B-01-02");
    }

    private void leaf(String name, String parentCode) {
        accounts.createLeaf(name, accounts.getAccountByCode(parentCode), null);
    }

    /** Books open 2026-10-01, locked through 2026-09-30 — every cut-over date is inside the lock. */
    public void openTheBooksOnTheCutOverDate() {
        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);
    }

    /** A whole cut-over organisation, ready to import into. Leaves the tenant in context. */
    public UUID newCutOverTenant(String prefix) {
        UUID tenantId = newTenant(prefix);
        TenantContextHolder.setTenantId(tenantId);
        seedChart();
        openTheBooksOnTheCutOverDate();
        return tenantId;
    }

    public Workbook template() throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(templates.generateCutOverTemplate()));
    }

    public byte[] bytesOf(Workbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    public ImportJob newJob() {
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("cutover.xlsx");
        return importJobs.save(job);
    }

    /** Overwrite one cell of a generated workbook, by sheet name and 0-based row/column. */
    public static void set(Workbook wb, String sheet, int row, int col, String value) {
        Row r = wb.getSheet(sheet).getRow(row);
        if (r.getCell(col) == null) r.createCell(col);
        r.getCell(col).setCellValue(value);
    }

    /**
     * The authentication a request thread would carry. Without one
     * {@code LeaseAccessPolicy} resolves "nobody" and every posting path answers
     * "Lease not found"; {@code PostingService} would also stamp every journal's
     * {@code posted_by} null.
     */
    public UUID authenticateAsTenantAdmin() {
        UUID userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
        return userId;
    }

    public void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }
}
