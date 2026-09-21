package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.BulkPostResult;
import com.datagami.rentaxis.core.service.cutover.ImportBatchDiscardService.DiscardResult;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.apache.poi.ss.usermodel.Workbook;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Discarding a cut-over import (ruling I4) — and the loop it closes.
 *
 * <p>A cut-over import refuses a property name, a renter e-mail or a contract
 * reference the organisation already holds, because resolving a ledger leaf by name
 * means a silent merge would join two towers' books (R10). That rule is what makes
 * a botched first import unrecoverable without this: the rows it left behind are
 * exactly what the second attempt trips over.</p>
 *
 * <p>The hard limit is here too, tested rather than discovered: once a batch has
 * been <em>posted</em>, its contracts are named by journal entries that can be
 * neither deleted nor re-pointed, so a reversed batch keeps everything it made and
 * says why. Its route back is Post again, not a re-import.</p>
 */
@SpringBootTest
@Testcontainers
class ImportBatchDiscardIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CutoverFixture fixture;
    @Autowired PortfolioImportService importService;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired ImportBatchDiscardService discardService;
    @Autowired ImportBatchService batches;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseLineRepository leaseLineRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PropertyAccountMappingRepository mappings;
    @Autowired RecognitionEntryRepository recognitionEntries;
    @Autowired TransactionTemplate tx;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = fixture.newCutOverTenant("DISCARD");
        fixture.authenticateAsTenantAdmin();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    private UUID importTheTemplate() throws Exception {
        try (Workbook wb = fixture.template()) {
            assertThat(importService.validateAll(wb).errors()).isEmpty();
            return contractPersist.persist(wb, fixture.newJob()).batchId();
        }
    }

    // ------------------------------------------------------------------
    // the loop the rule exists for
    // ------------------------------------------------------------------

    /**
     * Import, find it wrong, discard it, load the corrected workbook, post.
     *
     * <p>This is the case an accountant actually meets: the batch was never posted,
     * so nothing in the ledger names anything it made, and every row goes.</p>
     */
    @Test
    void aDraftBatchIsDiscardedWholeAndTheCorrectedWorkbookImportsAndPosts() throws Exception {
        UUID firstBatch = importTheTemplate();
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).hasSize(2);
            assertThat(propertyRepo.findAll()).hasSize(1);
            assertThat(unitRepo.findAll()).hasSize(2);
            assertThat(renterRepo.findAll()).hasSize(2);
        });

        DiscardResult result = discardService.discard(firstBatch);

        assertThat(result.kept()).isEmpty();
        assertThat(result.leasesDeleted()).isEqualTo(2);
        assertThat(result.unitsDeleted()).isEqualTo(2);
        assertThat(result.rentersDeleted()).isEqualTo(2);
        assertThat(result.propertiesDeleted()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(ImportBatchStatus.DISCARDED);
        assertThat(batches.get(firstBatch).getDiscardedAt()).isNotNull();

        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).isEmpty();
            assertThat(leaseLineRepo.findAll()).isEmpty();
            assertThat(chequeRepo.findAll()).isEmpty();
            assertThat(propertyRepo.findAll()).isEmpty();
            assertThat(unitRepo.findAll()).isEmpty();
            assertThat(renterRepo.findAll()).isEmpty();
            // The role mappings the import wrote go with the property; the ACCOUNTS
            // they pointed at are the tenant's chart and stay.
            assertThat(mappings.findAll()).isEmpty();
        });

        // The corrected workbook: same shape, one figure fixed. It imports cleanly,
        // which is the whole point — before the discard, every "already exists" rule
        // and the unique contract reference would have refused it.
        UUID secondBatch;
        try (Workbook wb = fixture.template()) {
            CutoverFixture.set(wb, "Contracts", 1, 16, "Annual rent (corrected)");
            List<ImportErrorDTO> errors = importService.validateAll(wb).errors();
            assertThat(errors).isEmpty();
            secondBatch = contractPersist.persist(wb, fixture.newJob()).batchId();
        }
        assertThat(secondBatch).isNotEqualTo(firstBatch);

        BulkPostResult posted = postService.post(secondBatch);
        assertThat(posted.leasesPosted()).isEqualTo(2);
        assertThat(posted.leasesFailed()).isZero();
        assertThat(posted.journalsPosted()).isGreaterThan(0);
    }

    /** A building the import made goes with it, in the right order. */
    @Test
    void aBatchThatMadeABuildingDeletesItToo() throws Exception {
        UUID batchId;
        try (Workbook wb = fixture.template()) {
            CutoverFixture.set(wb, "Units", 1, 1, "Tower One");
            CutoverFixture.set(wb, "Units", 2, 1, "Tower One");
            CutoverFixture.set(wb, "Contracts", 1, 3, "Tower One");
            CutoverFixture.set(wb, "Contracts", 3, 3, "Tower One");
            assertThat(importService.validateAll(wb).errors()).isEmpty();
            batchId = contractPersist.persist(wb, fixture.newJob()).batchId();
        }
        tx.executeWithoutResult(s -> assertThat(buildingRepo.findAll()).hasSize(1));

        DiscardResult result = discardService.discard(batchId);

        assertThat(result.buildingsDeleted()).isEqualTo(1);
        assertThat(result.kept()).isEmpty();
        tx.executeWithoutResult(s -> {
            assertThat(buildingRepo.findAll()).isEmpty();
            assertThat(unitRepo.findAll()).isEmpty();
            assertThat(propertyRepo.findAll()).isEmpty();
        });
    }

    // ------------------------------------------------------------------
    // what is kept, and why
    // ------------------------------------------------------------------

    /**
     * Somebody has let one of the imported flats in the meantime. The discard must
     * not take the unit — or the property it belongs to — out from under that lease.
     */
    @Test
    void aUnitSomebodyHasSinceLetIsKeptAndReported() throws Exception {
        UUID batchId = importTheTemplate();
        UUID keptUnitId = tx.execute(s -> unitRepo.findAll().get(0).getId());
        UUID outsiderLease = tx.execute(s -> {
            Unit unit = unitRepo.findById(keptUnitId).orElseThrow();
            Renter renter = new Renter();
            renter.setNameEn("Somebody Else");
            renter.setEmail("somebody.else@example.com");
            Renter saved = renterRepo.save(renter);
            Lease lease = new Lease();
            lease.setUnit(unit);
            lease.setRenter(saved);
            lease.setStartDate(java.time.LocalDate.of(2027, 1, 1));
            lease.setEndDate(java.time.LocalDate.of(2027, 12, 31));
            lease.setStatus(LeaseStatus.DRAFT);
            return leaseRepo.save(lease).getId();
        });

        DiscardResult result = discardService.discard(batchId);

        assertThat(result.status()).isEqualTo(ImportBatchStatus.DISCARDED);
        assertThat(result.kept())
                .anySatisfy(k -> {
                    assertThat(k.type()).isEqualTo("UNIT");
                    assertThat(k.id()).isEqualTo(keptUnitId);
                    assertThat(k.reason()).contains("lease(s) still exist on this unit");
                })
                .anySatisfy(k -> {
                    assertThat(k.type()).isEqualTo("PROPERTY");
                    assertThat(k.reason()).contains("still belong to this property");
                });
        tx.executeWithoutResult(s -> {
            assertThat(unitRepo.findById(keptUnitId)).isPresent();
            assertThat(leaseRepo.findById(outsiderLease)).isPresent();
            assertThat(propertyRepo.findAll()).hasSize(1);
            // The batch's own contracts are gone even so.
            assertThat(leaseRepo.findAll()).extracting(Lease::getId).containsExactly(outsiderLease);
        });
    }

    /**
     * The hard limit: once a batch has been posted, the ledger names its contracts
     * permanently. A reversed batch is still DISCARDED — it is finished with — but
     * nothing it created can go, and every row says why.
     */
    @Test
    void aReversedBatchKeepsTheContractsItsJournalsPermanentlyName() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        batches.reverse(batchId, CutoverFixture.AS_OF, "wrong workbook");

        DiscardResult result = discardService.discard(batchId);

        assertThat(result.leasesDeleted()).isZero();
        assertThat(result.kept())
                .filteredOn(k -> "LEASE".equals(k.type()))
                .hasSize(2)
                .allSatisfy(k -> {
                    assertThat(k.reason()).contains("journal entries permanently name this contract");
                    assertThat(k.reason()).contains("Post the batch again");
                });
        assertThat(result.status()).isEqualTo(ImportBatchStatus.DISCARDED);
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).hasSize(2)
                    .allSatisfy(l -> assertThat(l.getStatus()).isEqualTo(LeaseStatus.DRAFT));
            assertThat(propertyRepo.findAll()).hasSize(1);
        });
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    @Test
    void aPostedBatchCannotBeDiscarded() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        assertThatThrownBy(() -> discardService.discard(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("POSTED")
                .hasMessageContaining("Reverse it first");

        tx.executeWithoutResult(s -> assertThat(leaseRepo.findAll()).hasSize(2));
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }

    @Test
    void aBatchCannotBeDiscardedTwice() throws Exception {
        UUID batchId = importTheTemplate();
        discardService.discard(batchId);

        assertThatThrownBy(() -> discardService.discard(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DISCARDED");
    }

    /** A discarded batch is not a door back into the ledger. */
    @Test
    void aDiscardedBatchCannotBePosted() throws Exception {
        UUID batchId = importTheTemplate();
        discardService.discard(batchId);

        assertThatThrownBy(() -> postService.post(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("DISCARDED");
    }

    /** Another organisation's batch id is simply not there. */
    @Test
    void anotherOrganisationsBatchIsNotFound() throws Exception {
        UUID batchId = importTheTemplate();
        UUID other = fixture.newTenant("OTHER");
        TenantContextHolder.setTenantId(other);

        assertThatThrownBy(() -> discardService.discard(batchId))
                .isInstanceOf(com.datagami.rentaxis.api.exception.NotFoundException.class);

        TenantContextHolder.setTenantId(tenantId);
        tx.executeWithoutResult(s -> assertThat(leaseRepo.findAll()).hasSize(2));
        assertThat(recognitionEntries.findAll()).isNotNull();
    }
}
