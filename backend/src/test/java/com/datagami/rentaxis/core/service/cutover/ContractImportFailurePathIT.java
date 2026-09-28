package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.PortfolioTemplateService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * What the job row says when the persist phase blows up part-way.
 *
 * <p>Its own class because it replaces {@link ContractImportPersistService} with a
 * mock, which the rest of {@code ContractImportIT} needs to be real.</p>
 *
 * <p>The case is narrow and the consequence is not: the persist method sets
 * {@code job.importBatchId} on the in-memory job before its transaction commits, so
 * a failure at commit rolls the {@code import_batches} row back while the job object
 * in the executor's hand still points at it. The catch block then saves that job —
 * and the web, following the id, asks the batches screen for a batch that does not
 * exist. Counters get the same treatment for the same reason, which is why the v1
 * path already resets them.</p>
 */
@SpringBootTest
class ContractImportFailurePathIT extends AbstractPostgresIT {

    @Autowired PortfolioImportService importService;
    @Autowired PortfolioTemplateService templates;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired ImportJobRepository importJobs;

    @MockitoBean ContractImportPersistService persistService;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Fail-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        accounts.createLeaf("Rental Income ST1", accounts.getAccountByCode("C-01-01"), null);
        accounts.createLeaf("Rent Receivable - ST1", accounts.getAccountByCode("A-02-01"), null);
        accounts.createLeaf("Advance Rent - ST1", accounts.getAccountByCode("B-01-01"), null);
        accounts.createLeaf("Sample Bank - ST1", accounts.getAccountByCode("A-02-02"), null);
        accounts.createLeaf("PDC Receivable ST1", accounts.getAccountByCode("A-02-03"), null);
        accounts.createLeaf("Security Deposit ST1", accounts.getAccountByCode("B-01-02"), null);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void aPersistThatBlowsUpLeavesNoBatchIdAndNoCountersBehind() throws Exception {
        when(persistService.persist(any(), any(), any())).thenAnswer(inv -> {
            ImportJob job = inv.getArgument(1);
            // Exactly what the real method does just before its transaction commits.
            job.setImportBatchId(UUID.randomUUID());
            job.setPropertiesCreated(1);
            job.setLeasesCreated(1);
            job.setSchedulesCreated(2);
            throw new IllegalStateException("the database said no");
        });

        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("cutover.xlsx");
        UUID jobId = importJobs.save(job).getId();

        importService.processImportAsync(templates.generateCutOverTemplate(), job, tenantId);

        ImportJob after = awaitTerminal(jobId);
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getImportBatchId()).isNull();
        assertThat(after.getPropertiesCreated()).isZero();
        assertThat(after.getLeasesCreated()).isZero();
        assertThat(after.getSchedulesCreated()).isZero();
        // Break-it R3 data3 F4: the exception's text stays in the log; the job says
        // "Import failed — reference XXXXXXXX".
        assertThat(after.getErrors()).doesNotContain("the database said no")
                .containsPattern("Import failed — reference [0-9A-F]{8}").contains("import.failedRef");
    }

    /** Break-it R3 data3 F4: the two-tab upload — the loser's job names no table, column, value or org id. */
    @Test
    void aUniqueKeyRaceIsStoredAsAlreadyBeingImportedNotAsSql() throws Exception {
        when(persistService.persist(any(), any(), any())).thenAnswer(inv -> {
            java.sql.SQLException pg = new java.sql.SQLException("ERROR: duplicate key value violates unique constraint "
                    + "\"ux_properties_tenant_name_en_lower\"", "23505");
            java.sql.BatchUpdateException batch = new java.sql.BatchUpdateException("Batch entry 0 insert into properties "
                    + "(address,books_start_date,code) values (('x'),('" + tenantId + "'::uuid)) was aborted", "23505", 0, new int[0], pg);
            throw new org.springframework.dao.DataIntegrityViolationException("could not execute batch; SQL [insert into properties]",
                    new org.hibernate.exception.ConstraintViolationException("could not execute batch", batch,
                            "ux_properties_tenant_name_en_lower"));
        });

        ImportJob job = new ImportJob();
        job.setStatus("VALIDATING");
        job.setFileName("cutover.xlsx");
        UUID jobId = importJobs.save(job).getId();

        importService.processImportAsync(templates.generateCutOverTemplate(), job, tenantId);

        ImportJob after = awaitTerminal(jobId);
        assertThat(after.getStatus()).isEqualTo("FAILED");
        assertThat(after.getErrors()).contains("This file is already being imported — refresh to see the result")
                .contains("import.alreadyRunning")
                .doesNotContain("insert into").doesNotContain("ux_properties").doesNotContain(tenantId.toString());
    }

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
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Import job " + jobId + " never reached a terminal status");
    }
}
