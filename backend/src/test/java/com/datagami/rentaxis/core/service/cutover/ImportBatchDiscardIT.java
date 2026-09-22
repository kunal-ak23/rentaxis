package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * neither deleted nor re-pointed. So only a DRAFT batch can be discarded — a
 * REVERSED one is refused and told to post itself again, which is a route that
 * works, rather than being marked DISCARDED with its contracts still standing and
 * no way back.</p>
 *
 * <p>And discard takes the same row lock a post does, so the two cannot dismantle a
 * batch from both ends at once.</p>
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
    @Autowired LeasePostingService leasePosting;
    @Autowired TenantFiscalSettingsService fiscal;
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

    /** By the reference the sheet gave it, never by position in a list. */
    private Lease leaseOf(String externalContractRef) {
        return leaseRepo.findAll().stream()
                .filter(l -> externalContractRef.equals(l.getExternalContractRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lease with externalContractRef " + externalContractRef));
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
     * The hard limit, and the refusal it has to produce (review I1).
     *
     * <p>Once a batch has been posted, the ledger names its contracts permanently —
     * {@code journal_entries} has restricting keys to leases, units, properties and
     * renters, and its rows can be neither deleted nor re-pointed. So a reversed
     * batch has nothing a discard could remove, and marking it DISCARDED anyway used
     * to be the worst of both: the contracts stayed, the batch stopped being
     * postable, and the corrected workbook could not import either because those
     * contracts still hold the property name and the references. One click, no route
     * back.</p>
     *
     * <p>It is refused instead, and the sentence says which of the two things to do.</p>
     */
    @Test
    void aReversedBatchCannotBeDiscardedAndIsToldToPostAgain() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        batches.reverse(batchId, CutoverFixture.AS_OF, "wrong workbook");

        assertThatThrownBy(() -> discardService.discard(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("A reversed batch keeps its contracts; post it again or leave it reversed");

        // Nothing was touched, and the batch is still the thing you can post again.
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).hasSize(2)
                    .allSatisfy(l -> assertThat(l.getStatus()).isEqualTo(LeaseStatus.DRAFT));
            assertThat(propertyRepo.findAll()).hasSize(1);
        });
        // And that route really is open.
        assertThat(postService.post(batchId).leasesPosted()).isEqualTo(2);
    }

    /**
     * A DRAFT batch one of whose contracts somebody posted by hand.
     *
     * <p>This is the one live way a DRAFT batch can hold a contract the ledger names
     * — the ordinary `post the lease` door is open on an imported draft — and it is
     * why the discard asks the journal table rather than trusting the batch's own
     * status. The posted contract is kept and named; the rest of the batch still
     * goes.</p>
     */
    @Test
    void aContractSomebodyPostedByHandIsKeptRatherThanBreakingTheDiscard() throws Exception {
        UUID batchId = importTheTemplate();
        // A run that died after its first contract committed: the batch is still
        // DRAFT (markPosted never ran) and one of its contracts is on the books.
        // Sorted by contract date then reference, SAMPLE-0001 is the one that goes.
        assertThatThrownBy(() -> postService.post(batchId, progress -> {
            if (progress.processed() == 1) throw new IllegalStateException("the connection went away");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.DRAFT);
        UUID postedByHand = tx.execute(s -> leaseOf("SAMPLE-0001").getId());

        DiscardResult result = discardService.discard(batchId);

        assertThat(result.leasesDeleted()).isEqualTo(1);
        assertThat(result.kept())
                .filteredOn(k -> "LEASE".equals(k.type()))
                .singleElement()
                .satisfies(k -> {
                    assertThat(k.name()).isEqualTo("SAMPLE-0001");
                    assertThat(k.reason()).contains("journal entries permanently name this contract");
                });
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).extracting(Lease::getId).containsExactly(postedByHand);
            // Its unit and its renter are held by it, so they are kept too; the other
            // contract's are gone.
            assertThat(unitRepo.findAll()).hasSize(1);
            assertThat(renterRepo.findAll()).hasSize(1);
        });
    }

    // ------------------------------------------------------------------
    // discard and post cannot interleave (review I2)
    // ------------------------------------------------------------------

    /**
     * A discard that starts while a post is running used to read DRAFT, delete the
     * contracts the post had not reached yet — which the post then reported as "this
     * lease no longer exists" — and finish by calling `markDiscarded` on a batch the
     * post had just marked POSTED, throwing after the rows were gone.
     *
     * <p>Both now take the same row lock. The progress callback is the synchronisation
     * point: it runs inside the post's own transaction, so the discard is attempted at
     * a moment the post provably holds the lock.</p>
     */
    @Test
    void discardRacingARunningPostIsRefusedAndNothingIsDeleted() throws Exception {
        UUID batchId = importTheTemplate();
        AtomicReference<Throwable> discardError = new AtomicReference<>();
        CountDownLatch discardAttempted = new CountDownLatch(1);

        postService.post(batchId, progress -> {
            if (progress.processed() != 1) return;
            Thread other = new Thread(() -> {
                TenantContextHolder.setTenantId(tenantId);
                fixture.authenticateAsTenantAdmin();
                try {
                    discardService.discard(batchId);
                } catch (Throwable t) {
                    discardError.set(t);
                } finally {
                    TenantContextHolder.clear();
                    fixture.clearAuthentication();
                    discardAttempted.countDown();
                }
            });
            other.start();
            try {
                assertThat(discardAttempted.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertThat(discardError.get())
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("try again");
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findAll()).hasSize(2);
            assertThat(propertyRepo.findAll()).hasSize(1);
            assertThat(unitRepo.findAll()).hasSize(2);
        });
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }

    /** And the other way round: a post cannot start while the batch is being discarded. */
    @Test
    void aPostRacingADiscardIsRefused() throws Exception {
        UUID batchId = importTheTemplate();
        AtomicReference<Throwable> postError = new AtomicReference<>();

        // The discard's own lock, taken the way the discard takes it and held for the
        // length of a transaction, which is what the running discard is doing.
        tx.executeWithoutResult(s -> {
            batches.lockForRun(batchId, "discarded");
            Thread other = new Thread(() -> {
                TenantContextHolder.setTenantId(tenantId);
                fixture.authenticateAsTenantAdmin();
                try {
                    postService.post(batchId);
                } catch (Throwable t) {
                    postError.set(t);
                } finally {
                    TenantContextHolder.clear();
                    fixture.clearAuthentication();
                }
            });
            other.start();
            try {
                other.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertThat(postError.get())
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("try again");
        tx.executeWithoutResult(s -> assertThat(leaseRepo.findAll()).hasSize(2)
                .allSatisfy(l -> assertThat(l.getStatus()).isEqualTo(LeaseStatus.DRAFT)));
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
                .hasMessageContaining("reverse it first");

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
