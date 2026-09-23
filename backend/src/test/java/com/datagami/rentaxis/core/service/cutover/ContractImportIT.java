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
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
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
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

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
class ContractImportIT extends AbstractPostgresIT {

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
    @Autowired BuildingRepository buildingRepo;
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
        leaf("Rental Income ST1", "C-01-01");
        leaf("Rent Receivable - ST1", "A-02-01");
        leaf("Advance Rent - ST1", "B-01-01");
        leaf("Sample Bank - ST1", "A-02-02");
        leaf("PDC Receivable ST1", "A-02-03");
        leaf("Security Deposit ST1", "B-01-02");
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

    /** By the reference the sheet gave it, never by position in a list. */
    private Lease leaseOf(String externalContractRef) {
        return leaseRepo.findAll().stream()
                .filter(l -> externalContractRef.equals(l.getExternalContractRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lease with externalContractRef " + externalContractRef));
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
            assertThat(summary.unitsCreated()).isEqualTo(2);
            assertThat(summary.rentersCreated()).isEqualTo(2);
            assertThat(summary.contractsCreated()).isEqualTo(2);
            assertThat(summary.chequesCreated()).isEqualTo(3);
            assertThat(summary.mappingsCreated()).isEqualTo(6);

            List<UUID> leaseIds = batches.leaseIds(summary.batchId());
            assertThat(leaseIds).hasSize(2);

            tx.executeWithoutResult(s -> {
                Lease lease = leaseOf("SAMPLE-0001");
                assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
                assertThat(lease.getExternalContractRef()).isEqualTo("SAMPLE-0001");
                assertThat(lease.getEjariNumber()).isEqualTo("EJ-2026-0001");
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
                            assertThat(l.getCreditAccount().getName()).isEqualTo("Advance Rent - ST1");
                        });
            });

            // The unit stays VACANT: a DRAFT lease reserves nothing, and the batch
            // post is what makes the tenancy real.
            tx.executeWithoutResult(s ->
                    assertThat(unitRepo.findAll()).hasSize(2)
                            .allSatisfy(u -> assertThat(u.getStatus()).isEqualTo(UnitStatus.VACANT)));

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
            contractPersist.persist(wb, job);
            UUID leaseId = tx.execute(s -> leaseOf("SAMPLE-0001").getId());

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
                assertThat(first.getChequeNumber()).isEqualTo("100001");
                assertThat(first.getAmount()).isEqualByComparingTo("31000.00");
                assertThat(first.getChequeDate()).isEqualTo(LocalDate.of(2026, 9, 24));
                assertThat(first.getPostingDate()).isEqualTo(LocalDate.of(2026, 9, 11));
                assertThat(first.getImportedStatus()).isEqualTo(ChequeStatus.CLEARED);
                // The sheet gives only a cleared date — PACT does not export the day
                // paper reached the bank — so it is banked on the day it cleared.
                assertThat(first.getImportedDepositedOn()).isEqualTo(LocalDate.of(2026, 9, 25));
                assertThat(first.getImportedClearedOn()).isEqualTo(LocalDate.of(2026, 9, 25));
                assertThat(first.getImportedBouncedOn()).isNull();
                // Where the cleared funds will land: the property's BANK mapping,
                // which came from the sheet's BankAccount column.
                assertThat(first.getDebitAccount().getName()).isEqualTo("Sample Bank - ST1");

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
                assertThat(name(p.getId(), AccountRole.RENTAL_INCOME)).isEqualTo("Rental Income ST1");
                assertThat(name(p.getId(), AccountRole.RENT_RECEIVABLE)).isEqualTo("Rent Receivable - ST1");
                assertThat(name(p.getId(), AccountRole.ADVANCE_RENT)).isEqualTo("Advance Rent - ST1");
                assertThat(name(p.getId(), AccountRole.BANK)).isEqualTo("Sample Bank - ST1");
                assertThat(name(p.getId(), AccountRole.PDC_RECEIVABLE)).isEqualTo("PDC Receivable ST1");
                assertThat(name(p.getId(), AccountRole.SECURITY_DEPOSIT)).isEqualTo("Security Deposit ST1");
                // ADMIN_FEE is not one of the six columns, so it came from the template.
                assertThat(name(p.getId(), AccountRole.ADMIN_FEE)).isEqualTo("Admin Fee - Sample Tower");
            });
        }
    }

    private String name(UUID propertyId, AccountRole role) {
        return mappings.findByPropertyIdAndRole(propertyId, role)
                .map(m -> m.getAccount().getName())
                .orElse(null);
    }

    /**
     * What the batch created, beside its leases — the record Task 11's discard
     * needs to undo an import exactly rather than approximately (review I4).
     */
    @Test
    void theBatchRecordsThePropertiesUnitsAndRentersItCreated() throws Exception {
        try (Workbook wb = template()) {
            UUID batchId = contractPersist.persist(wb, newJob()).batchId();

            tx.executeWithoutResult(s -> {
                UUID propertyId = propertyRepo.findAll().get(0).getId();
                List<UUID> unitIds = unitRepo.findAll().stream().map(u -> u.getId()).toList();
                List<UUID> renterIds = renterRepo.findAll().stream().map(r -> r.getId()).toList();

                var created = batches.createdEntities(batchId);
                assertThat(created).extracting(e -> e.getEntityType(), e -> e.getEntityId())
                        .contains(org.assertj.core.groups.Tuple.tuple(ImportedEntityType.PROPERTY, propertyId));
                assertThat(created).filteredOn(e -> e.getEntityType() == ImportedEntityType.UNIT)
                        .extracting(e -> e.getEntityId())
                        .containsExactlyInAnyOrderElementsOf(unitIds);
                assertThat(created).filteredOn(e -> e.getEntityType() == ImportedEntityType.RENTER)
                        .extracting(e -> e.getEntityId())
                        .containsExactlyInAnyOrderElementsOf(renterIds);
                // The template's sample has no building, so none is recorded. The
                // workbook that DOES name one is the next test — a batch that
                // recorded no buildings because the fixture had none proves nothing
                // about a batch that makes them.
                assertThat(created).noneMatch(e -> e.getEntityType() == ImportedEntityType.BUILDING);
            });
        }
    }

    /**
     * A workbook that names a building: the row it creates is recorded like every
     * other, because Task 11's discard has to delete exactly what the batch made
     * and a tower left behind blocks the corrected workbook's unit numbers.
     *
     * <p>Both units share one building name, so this also pins that the second flat
     * does not record a second building.</p>
     */
    @Test
    void aBuildingTheImportCreatesIsRecordedOnTheBatchExactlyOnce() throws Exception {
        try (Workbook wb = template()) {
            set(wb, "Units", 1, 1, "Tower One");
            set(wb, "Units", 2, 1, "Tower One");
            set(wb, "Contracts", 1, 3, "Tower One");
            set(wb, "Contracts", 3, 3, "Tower One");

            assertThat(validate(wb)).isEmpty();
            UUID batchId = contractPersist.persist(wb, newJob()).batchId();

            tx.executeWithoutResult(s -> {
                List<UUID> buildingIds = buildingRepo.findAll().stream().map(b -> b.getId()).toList();
                assertThat(buildingIds).hasSize(1);
                assertThat(batches.createdEntities(batchId))
                        .filteredOn(e -> e.getEntityType() == ImportedEntityType.BUILDING)
                        .extracting(e -> e.getEntityId())
                        .containsExactlyInAnyOrderElementsOf(buildingIds);
                // And the units really did land in it, so the link is not a record of
                // a row nothing uses.
                assertThat(unitRepo.findAll()).hasSize(2)
                        .allSatisfy(u -> assertThat(u.getBuilding()).isNotNull());
            });
        }
    }

    /**
     * The VAT-bearing contract, persisted rather than only stubbed (review M5): the
     * figure LeaseVat computed at import is the figure LeasePostingService will
     * compare the grid against at post, and this is where the two meet on real rows.
     */
    @Test
    void aVatBearingContractPersistsItsGrossAsTheChequeTotal() throws Exception {
        try (Workbook wb = template()) {
            assertThat(validate(wb)).isEmpty();
            contractPersist.persist(wb, newJob());

            tx.executeWithoutResult(s -> {
                Lease vatLease = leaseOf("SAMPLE-0002");
                assertThat(leaseLineRepo.findByLease_IdOrderBySeqNoAsc(vatLease.getId()))
                        .singleElement()
                        .satisfies(l -> {
                            assertThat(l.isVatApplicable()).isTrue();
                            assertThat(l.getNetAmount()).isEqualByComparingTo("21000.00");
                        });
                assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(vatLease.getId()))
                        .singleElement()
                        .satisfies(c -> assertThat(c.getAmount()).isEqualByComparingTo("22050.00"));
            });
        }
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
        assertThat(after.getLeasesCreated()).isEqualTo(2);
        assertThat(after.getPropertiesCreated()).isEqualTo(1);
        assertThat(after.getSchedulesCreated()).isEqualTo(3);
        assertThat(after.getErrors()).contains("contractsCreated");
        assertThat(batches.leaseIds(after.getImportBatchId())).hasSize(2);
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
        theirs.setNameEn("Sample Tower");
        theirs.setEmirate(Emirate.DUBAI);
        UUID theirPropertyId = propertyRepo.save(theirs).getId();
        Renter theirRenter = new Renter();
        theirRenter.setNameEn("Sample Renter One");
        theirRenter.setEmail("sample.renter.one@example.com");
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
                    .hasSize(2)
                    .allSatisfy(r -> assertThat(r.getId()).isNotEqualTo(theirRenterId));
            assertThat(leaseRepo.findAll()).hasSize(2)
                    .allSatisfy(l -> assertThat(l.getTenantId()).isEqualTo(tenantId));
            // Both organisations were seeded with identically NAMED accounts, so a
            // leak through accountRepository.findAll() would still have resolved and
            // still produced a valid-looking mapping. The ids are what tell them apart.
            UUID propertyId = propertyRepo.findAll().get(0).getId();
            assertThat(mappings.findByPropertyId(propertyId))
                    .isNotEmpty()
                    .allSatisfy(m -> assertThat(m.getAccount().getTenantId()).isEqualTo(tenantId));
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
        existing.setNameEn("Sample Tower");
        existing.setEmirate(Emirate.DUBAI);
        propertyRepo.save(existing);

        try (Workbook wb = template()) {
            assertThat(validate(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(org.assertj.core.groups.Tuple.tuple("Properties", "PropertyName"));
        }
    }
}
