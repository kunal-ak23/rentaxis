package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over import end to end: download the template, upload it, and get DRAFT
 * leases with their lines, their cheque grid and their property account mappings —
 * all in one reversible batch, with no journal anywhere.
 *
 * <p>The workbook under test is the template this release actually hands the
 * accountant, not a fixture built to suit the parser. A template whose own sample
 * rows do not import is a support call on the first day of every cut-over, and it
 * is the kind of thing a hand-built fixture never catches.</p>
 */
@SpringBootTest
@Testcontainers
class ContractImportIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired PortfolioTemplateService templates;
    @Autowired PortfolioImportService importService;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ImportBatchService batches;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired ImportJobRepository importJobs;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseLineRepository leaseLineRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyAccountMappingRepository mappings;
    @Autowired JournalEntryRepository entries;
    @Autowired TransactionTemplate tx;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = newTenant("CUT");
        TenantContextHolder.setTenantId(tenantId);
        seedChartAndTheClientsSixLeaves();
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID newTenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + "-" + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    /**
     * The chart the cut-over template's sample row names. These are the client's own
     * account names (their "property mapping ledgers" export), which are NOT the
     * names the account template would generate — that is the point of the six
     * columns.
     */
    private void seedChartAndTheClientsSixLeaves() {
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        leaf("Rental Income Tulip 7", "C-01-01");
        leaf("Rent Receivable - Tulip 7", "A-02-01");
        leaf("Advance Rent - Tulip 7", "B-01-01");
        leaf("Emirates Islamic - Tulip 7", "A-02-02");
        leaf("PDC Receivable Tulip 7", "A-02-03");
        leaf("Security Deposit Tulip 7", "B-01-02");
    }

    private Account leaf(String name, String parentCode) {
        return accounts.createLeaf(name, accounts.getAccountByCode(parentCode), null);
    }

    private ImportJob newJob() {
        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("cutover.xlsx");
        return importJobs.save(job);
    }

    private Workbook template() throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(templates.generateCutOverTemplate()));
    }

    private static void set(Workbook wb, String sheet, int row, int col, String value) {
        Row r = wb.getSheet(sheet).getRow(row);
        if (r.getCell(col) == null) r.createCell(col);
        r.getCell(col).setCellValue(value);
    }

    private List<ImportErrorDTO> validate(Workbook wb) {
        return importService.validateAll(wb).errors();
    }

    /**
     * {@code processImportAsync} really is asynchronous — it is called on the
     * Spring proxy, so {@code @Async("importExecutor")} applies and the job row is
     * still VALIDATING when the call returns. Polling the row is what the web client
     * does through the status endpoint, so it is also the honest way to test it.
     */
    private ImportJob awaitTerminal(UUID jobId) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            ImportJob job = importJobs.findById(jobId).orElseThrow();
            String status = job.getStatus();
            if ("COMPLETED".equals(status) || "VALIDATION_FAILED".equals(status) || "FAILED".equals(status)) {
                return job;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for import job " + jobId, e);
            }
        }
        throw new AssertionError("Import job " + jobId + " never reached a terminal status");
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    void theCutOverTemplateImportsAsDraftLeasesInOneBatch() throws Exception {
        try (Workbook wb = template()) {
            assertThat(validate(wb)).isEmpty();

            ImportJob job = newJob();
            ContractImportPersistService.ContractImportSummary summary = contractPersist.persist(wb, job);

            assertThat(summary.propertiesCreated()).isEqualTo(1);
            assertThat(summary.unitsCreated()).isEqualTo(1);
            assertThat(summary.rentersCreated()).isEqualTo(1);
            assertThat(summary.contractsCreated()).isEqualTo(1);
            assertThat(summary.chequesCreated()).isEqualTo(2);
            assertThat(summary.mappingsCreated()).isEqualTo(6);

            List<UUID> leaseIds = batches.leaseIds(summary.batchId());
            assertThat(leaseIds).hasSize(1);

            tx.executeWithoutResult(s -> {
                Lease lease = leaseRepo.findById(leaseIds.get(0)).orElseThrow();
                assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
                assertThat(lease.getExternalContractRef()).isEqualTo("TLP7/681");
                assertThat(lease.getEjariNumber()).isEqualTo("EJ-2026-0681");
                assertThat(lease.getContractDate()).isEqualTo(LocalDate.of(2026, 9, 11));
                assertThat(lease.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 24));
                assertThat(lease.getEndDate()).isEqualTo(LocalDate.of(2027, 9, 23));
                assertThat(lease.getGracePeriodDays()).isEqualTo(5);
                // The grid is the instalment plan, so the lease's payment terms are
                // what the sheet actually listed rather than a number nobody typed.
                assertThat(lease.getPaymentTerms()).isEqualTo(2);
                // Derived from the lines by LeaseService.syncDerivedTotals, not from a column.
                assertThat(lease.getRentAmount()).isEqualByComparingTo("51000.00");
                assertThat(lease.getDepositAmount()).isEqualByComparingTo("5000.00");

                assertThat(leaseLineRepo.findByLease_IdOrderBySeqNoAsc(lease.getId()))
                        .hasSize(2)
                        .anySatisfy(l -> {
                            assertThat(l.getChargeType().getCode()).isEqualTo("RENT");
                            assertThat(l.getNetAmount()).isEqualByComparingTo("51000.00");
                            assertThat(l.getCreditAccount().getName()).isEqualTo("Advance Rent - Tulip 7");
                        });
            });

            // The unit stays VACANT: a DRAFT lease reserves nothing, and the batch
            // post is what makes the tenancy real.
            tx.executeWithoutResult(s ->
                    assertThat(unitRepo.findAll()).singleElement()
                            .satisfies(u -> assertThat(u.getStatus()).isEqualTo(UnitStatus.VACANT)));

            // Nothing posted. This is the whole contract with Task 11.
            assertThat(entries.findByImportBatchIdOrderByCreatedAtAsc(summary.batchId())).isEmpty();
            tx.executeWithoutResult(s -> assertThat(entries.findAll()).isEmpty());

            assertThat(importJobs.findById(job.getId()).orElseThrow().getImportBatchId())
                    .isEqualTo(summary.batchId());
        }
    }

    /** Every cheque is DRAFT with its replay instruction parked beside it — what Task 11 reads. */
    @Test
    void everyChequeIsADraftRowCarryingTheStatusAndDatesTheSheetAsked() throws Exception {
        try (Workbook wb = template()) {
            ImportJob job = newJob();
            UUID batchId = contractPersist.persist(wb, job).batchId();
            UUID leaseId = batches.leaseIds(batchId).get(0);

            tx.executeWithoutResult(s -> {
                List<Cheque> rows = chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId);
                assertThat(rows).hasSize(2);
                assertThat(rows).allSatisfy(c -> {
                    assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT);
                    assertThat(c.getPdrJournalId()).isNull();
                    // The real columns stay untouched: nothing has happened to this
                    // money yet, and a DRAFT row claiming a deposit would be a lie.
                    assertThat(c.getDepositedAt()).isNull();
                    assertThat(c.getClearedAt()).isNull();
                    assertThat(c.getBouncedAt()).isNull();
                });

                Cheque first = rows.get(0);
                assertThat(first.getChequeNumber()).isEqualTo("000101");
                assertThat(first.getAmount()).isEqualByComparingTo("31000.00");
                assertThat(first.getChequeDate()).isEqualTo(LocalDate.of(2026, 9, 24));
                assertThat(first.getPostingDate()).isEqualTo(LocalDate.of(2026, 9, 11));
                assertThat(first.getImportedStatus()).isEqualTo(ChequeStatus.CLEARED);
                assertThat(first.getImportedDepositedOn()).isEqualTo(LocalDate.of(2026, 9, 24));
                assertThat(first.getImportedClearedOn()).isEqualTo(LocalDate.of(2026, 9, 25));
                assertThat(first.getImportedBouncedOn()).isNull();
                // Where the cleared funds will land: the property's BANK mapping,
                // which came from the sheet's BankAccount column.
                assertThat(first.getDebitAccount().getName()).isEqualTo("Emirates Islamic - Tulip 7");

                Cheque second = rows.get(1);
                assertThat(second.getImportedStatus()).isEqualTo(ChequeStatus.REGISTERED);
                assertThat(second.getImportedDepositedOn()).isNull();
                assertThat(second.getImportedClearedOn()).isNull();
            });
        }
    }

    /** The sheet's six names win; the template fills the roles it says nothing about. */
    @Test
    void theSheetsAccountsAreMappedAndTheTemplateFillsTheRest() throws Exception {
        try (Workbook wb = template()) {
            contractPersist.persist(wb, newJob());

            tx.executeWithoutResult(s -> {
                Property p = propertyRepo.findAll().get(0);
                assertThat(name(p.getId(), AccountRole.RENTAL_INCOME)).isEqualTo("Rental Income Tulip 7");
                assertThat(name(p.getId(), AccountRole.RENT_RECEIVABLE)).isEqualTo("Rent Receivable - Tulip 7");
                assertThat(name(p.getId(), AccountRole.ADVANCE_RENT)).isEqualTo("Advance Rent - Tulip 7");
                assertThat(name(p.getId(), AccountRole.BANK)).isEqualTo("Emirates Islamic - Tulip 7");
                assertThat(name(p.getId(), AccountRole.PDC_RECEIVABLE)).isEqualTo("PDC Receivable Tulip 7");
                assertThat(name(p.getId(), AccountRole.SECURITY_DEPOSIT)).isEqualTo("Security Deposit Tulip 7");
                // ADMIN_FEE is not one of the six columns, so it came from the template.
                assertThat(name(p.getId(), AccountRole.ADMIN_FEE)).isEqualTo("Admin Fee - Tulip Oasis 7");
            });
        }
    }

    private String name(UUID propertyId, AccountRole role) {
        return mappings.findByPropertyIdAndRole(propertyId, role)
                .map(m -> m.getAccount().getName())
                .orElse(null);
    }

    /** A role the sheet leaves blank is reported, never guessed (spec §10.3). */
    @Test
    void aBlankAccountColumnIsAWarningNamingTheRole() throws Exception {
        try (Workbook wb = template()) {
            set(wb, "Properties", 1, 10, "");   // PdcReceivableAccount
            assertThat(validate(wb)).isEmpty();
            assertThat(importService.validateAll(wb).warnings())
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("PdcReceivableAccount");
                        assertThat((String) t.toList().get(1)).contains("PDC_RECEIVABLE");
                    });
        }
    }

    // ------------------------------------------------------------------
    // nothing is written until everything validates
    // ------------------------------------------------------------------

    @Test
    void aWorkbookWithOneBadCellPersistsNothingAtAll() throws Exception {
        try (Workbook wb = template()) {
            set(wb, "Properties", 1, 9, "Bank Of Nowhere");

            ImportJob job = newJob();
            importService.processImportAsync(templateBytesOf(wb), job, tenantId);

            ImportJob after = awaitTerminal(job.getId());
            assertThat(after.getStatus()).isEqualTo("VALIDATION_FAILED");
            assertThat(after.getImportBatchId()).isNull();
            assertThat(after.getErrors()).contains("Bank Of Nowhere");

            tx.executeWithoutResult(s -> {
                assertThat(propertyRepo.findAll()).isEmpty();
                assertThat(leaseRepo.findAll()).isEmpty();
                assertThat(chequeRepo.findAll()).isEmpty();
                assertThat(renterRepo.findAll()).isEmpty();
            });
            assertThat(batches.list()).isEmpty();
        }
    }

    private static byte[] templateBytesOf(Workbook wb) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    /** The async orchestrator is the real door: same job row, same statuses, new persist target. */
    @Test
    void theAsyncOrchestratorRunsTheV2PathAndCompletesTheJob() throws Exception {
        byte[] bytes = templates.generateCutOverTemplate();
        ImportJob job = newJob();

        importService.processImportAsync(bytes, job, tenantId);

        ImportJob after = awaitTerminal(job.getId());
        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        assertThat(after.getImportBatchId()).isNotNull();
        assertThat(after.getLeasesCreated()).isEqualTo(1);
        assertThat(after.getPropertiesCreated()).isEqualTo(1);
        assertThat(after.getSchedulesCreated()).isEqualTo(2);
        assertThat(after.getErrors()).contains("contractsCreated");
        assertThat(batches.leaseIds(after.getImportBatchId())).hasSize(1);
    }

    // ------------------------------------------------------------------
    // tenant isolation — this class leaked at the service layer once
    // ------------------------------------------------------------------

    /**
     * A cut-over for one organisation must not see another's property, renter or
     * chart, even when every name collides. The import runs on the executor thread,
     * which has no request-bound session, so the Hibernate tenant filter is only on
     * inside a transaction — an untransacted read here reads everybody.
     */
    @Test
    void anImportForOneOrganisationNeitherSeesNorWritesAnothers() throws Exception {
        UUID otherTenant = newTenant("OTHER");
        TenantContextHolder.setTenantId(otherTenant);
        seedChartAndTheClientsSixLeaves();
        Property theirs = new Property();
        theirs.setNameEn("Tulip Oasis 7");
        theirs.setEmirate(Emirate.DUBAI);
        UUID theirPropertyId = propertyRepo.save(theirs).getId();
        Renter theirRenter = new Renter();
        theirRenter.setNameEn("Islam Mamanov");
        theirRenter.setEmail("islam@example.com");
        UUID theirRenterId = renterRepo.save(theirRenter).getId();
        TenantContextHolder.clear();

        // The same names are free in OUR organisation, so the import goes through.
        byte[] bytes = templates.generateCutOverTemplate();
        ImportJob job;
        TenantContextHolder.setTenantId(tenantId);
        job = newJob();
        TenantContextHolder.clear();

        importService.processImportAsync(bytes, job, tenantId);

        TenantContextHolder.setTenantId(tenantId);
        ImportJob after = awaitTerminal(job.getId());
        assertThat(after.getStatus()).isEqualTo("COMPLETED");

        tx.executeWithoutResult(s -> {
            assertThat(propertyRepo.findAll())
                    .singleElement()
                    .satisfies(p -> assertThat(p.getId()).isNotEqualTo(theirPropertyId));
            assertThat(renterRepo.findAll())
                    .singleElement()
                    .satisfies(r -> assertThat(r.getId()).isNotEqualTo(theirRenterId));
            assertThat(leaseRepo.findAll()).singleElement()
                    .satisfies(l -> assertThat(l.getTenantId()).isEqualTo(tenantId));
        });

        // And the other organisation is untouched.
        TenantContextHolder.setTenantId(otherTenant);
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).isEmpty();
            assertThat(chequeRepo.findAll()).isEmpty();
            assertThat(propertyRepo.findAll()).singleElement()
                    .satisfies(p -> assertThat(p.getId()).isEqualTo(theirPropertyId));
        });
        assertThat(batches.list()).isEmpty();
        TenantContextHolder.setTenantId(tenantId);
    }

    /** The same name inside one organisation is the error the merge rule exists for. */
    @Test
    void aPropertyTheOrganisationAlreadyHasIsAValidationError() throws Exception {
        Property existing = new Property();
        existing.setNameEn("Tulip Oasis 7");
        existing.setEmirate(Emirate.DUBAI);
        propertyRepo.save(existing);

        try (Workbook wb = template()) {
            assertThat(validate(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(org.assertj.core.groups.Tuple.tuple("Properties", "PropertyName"));
        }
    }
}
