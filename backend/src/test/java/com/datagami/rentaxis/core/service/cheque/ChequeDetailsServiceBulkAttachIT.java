package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guards on bulk attach, against a real database.
 *
 * <p>These behaviours came over from the payment-schedule bulk attach and its test
 * went with the method it tested; they live on here because the failure modes did.
 * Two scans claiming one row resolve to one entity and the second overwrites the
 * first. Two scans carrying one cheque number, or a number another row on the lease
 * already holds, are the same instrument recorded twice — the unique index would
 * refuse the second as a 409 naming a constraint, and the operator is holding a pile
 * of paper and needs to know which piece to pull out of it.</p>
 *
 * <p>Every refusal is all-or-nothing and names every bad row at once, so the screen
 * can highlight them together rather than reporting the first one five times.</p>
 */
@SpringBootTest
class ChequeDetailsServiceBulkAttachIT extends AbstractPostgresIT {

    @Autowired ChequeDetailsService details;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 1, 5);
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2027, 1, 31);

    private LeaseTestFixtures fixtures;
    private UUID tenantId;
    private UUID leaseId;
    private List<Cheque> register;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        tenantId = fixtures.tenantId();
        // Unnumbered on purpose: the numbers are what this test is about. Only an
        // import can post unnumbered PDCs now (#80), so the fixture posts through
        // that door.
        leaseId = fixtures.postedLeaseUnnumbered(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4).lease().getId();
        register = reread();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private List<Cheque> reread() {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private BulkAttachChequeItem item(UUID chequeId, String number) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setChequeId(chequeId);
        it.setChequeNumber(number);
        it.setChequeDate(LocalDate.of(2026, 3, 1));
        it.setBankName("Emirates NBD");
        it.setPayerName("Test Renter");
        it.setImageUrl("https://blob/x.jpg");
        it.setImageBlobPath(issuedImage(tenantId));
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }

    @org.springframework.beans.factory.annotation.Autowired
    com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository imageUploads;

    /** A scan as /cheques/extract records it: bulk-attach takes only server-issued paths (C-F2). */
    private String issuedImage(UUID tenantId) {
        com.datagami.rentaxis.domain.entity.ChequeImageUpload u = new com.datagami.rentaxis.domain.entity.ChequeImageUpload();
        u.setTenantId(tenantId);
        u.setBlobPath("cheques/" + UUID.randomUUID() + ".jpg");
        u.setImageUrl("https://blob/" + u.getBlobPath());
        return imageUploads.save(u).getBlobPath();
    }

    private static List<String> reasons(BulkAttachValidationException e) {
        return e.getRows().stream().map(BulkAttachErrorRow::reason).toList();
    }

    /** Two scans assigned to one row: the second would silently overwrite the first. */
    @Test
    void duplicateChequeIdInTheRequestIsRefusedAndNothingIsWritten() {
        UUID id = register.getFirst().getId();

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(item(id, "C-1"), item(id, "C-2"))))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> {
                    assertThat(reasons((BulkAttachValidationException) e))
                            .contains("duplicate_cheque_id_in_request");
                    assertThat(((BulkAttachValidationException) e).isConflict()).isFalse();
                });

        assertThat(reread()).allSatisfy(c -> assertThat(c.getChequeNumber()).isNull());
    }

    /** One cheque number on two rows is one instrument recorded twice. */
    @Test
    void duplicateChequeNumberInTheRequestIsRefusedAndNothingIsWritten() {
        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(
                item(register.get(0).getId(), "C-SAME"),
                item(register.get(1).getId(), "C-SAME"))))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e))
                        .contains("duplicate_cheque_number_in_request"));

        assertThat(reread()).allSatisfy(c -> assertThat(c.getChequeNumber()).isNull());
    }

    /**
     * A number a row this call is not touching already holds. Reported as a 409 with
     * the offending row named, rather than left to {@code ux_cheques_lease_number}.
     */
    @Test
    void aNumberAlreadyUsedOnTheLeaseIsAConflictNamingTheRow() {
        details.bulkAttach(leaseId, List.of(item(register.get(0).getId(), "C-100")));
        UUID second = register.get(1).getId();

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(item(second, "C-100"))))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> {
                    BulkAttachValidationException ex = (BulkAttachValidationException) e;
                    assertThat(reasons(ex)).containsExactly("cheque_number_already_used_on_lease");
                    assertThat(ex.getRows()).extracting(BulkAttachErrorRow::chequeId).containsExactly(second);
                    assertThat(ex.isConflict()).isTrue();
                });

        assertThat(reread().get(1).getChequeNumber()).isNull();
        // And the row that legitimately holds the number kept it.
        assertThat(reread().getFirst().getChequeNumber()).isEqualTo("C-100");
    }

    /** Re-attaching the same number to the same row is not a clash with itself. */
    @Test
    void aRowMayKeepItsOwnNumberAcrossReAttachment() {
        UUID id = register.getFirst().getId();
        details.bulkAttach(leaseId, List.of(item(id, "C-100")));

        details.bulkAttach(leaseId, List.of(item(id, "C-100")));

        assertThat(reread().getFirst().getChequeNumber()).isEqualTo("C-100");
    }

    @Test
    void anUnknownLeaseIsNotFound() {
        assertThatThrownBy(() -> details.bulkAttach(UUID.randomUUID(),
                List.of(item(register.getFirst().getId(), "C-1"))))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Another organisation's lease does not exist to this one.
     *
     * <p>Asserted with the tenant context cleared as well as switched, because those
     * are two different holes. Switched, the Hibernate filter catches it. Cleared,
     * the filter is not enabled at all — {@code TenantAspect} only turns it on when
     * there is a context — so the lookup would succeed and {@code requireManageable}
     * would wave a TENANT_ADMIN through onto somebody else's register. Only the
     * service's own guard closes that one.</p>
     */
    @Test
    void anotherTenantsLeaseIsNotFoundWithOrWithoutATenantContext() {
        UUID otherTenantsLease = leaseId;
        List<BulkAttachChequeItem> payload = List.of(item(register.getFirst().getId(), "C-1"));

        // A different organisation, authenticated as its own admin.
        fixtures.newTenant();
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThatThrownBy(() -> details.bulkAttach(otherTenantsLease, payload))
                .isInstanceOf(NotFoundException.class);

        // No tenant at all: the filter is off, so the guard is the only thing left.
        TenantContextHolder.clear();
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThatThrownBy(() -> details.bulkAttach(otherTenantsLease, payload))
                .isInstanceOf(NotFoundException.class);

        TenantContextHolder.setTenantId(fixtures.tenantId());
        assertThat(reread()).allSatisfy(c -> assertThat(c.getChequeNumber()).isNull());
    }

    /** A cheque of another lease, named against this one. */
    @Test
    void aChequeOfAnotherLeaseIsRefused() {
        Property otherProperty = fixtures.createProperty("OTH");
        Unit otherUnit = fixtures.createUnit(otherProperty, "909");
        Renter otherRenter = fixtures.createRenter("Other Renter");
        UUID otherLease = fixtures.postedLease(otherUnit, otherRenter, CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, null).lease().getId();
        UUID foreignCheque = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(otherLease)).getFirst().getId();

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(item(foreignCheque, "C-1"))))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e))
                        .containsExactly("cheque_not_in_lease"));
    }

    /**
     * The single-row edit refuses a call with no tenant context, exactly as the
     * batch does.
     *
     * <p>Without a context {@code TenantAspect} never enables the Hibernate filter,
     * so the locking lookup returns whatever row the id names — including another
     * organisation's — and {@code requireManageable} lets a TENANT_ADMIN through
     * because it answers on roles, not on tenancy. The guard inside {@code lock} is
     * the only thing standing there.</p>
     */
    @Test
    void updateDetailsRefusesACallWithNoTenantContext() {
        UUID id = register.getFirst().getId();
        ChequeRowInput edit = new ChequeRowInput(null, null, null, "C-NO-TENANT",
                LocalDate.of(2026, 3, 1), "Emirates NBD", null, null, null, null, null);

        TenantContextHolder.clear();
        LeaseTestFixtures.authenticateAsTenantAdmin();

        assertThatThrownBy(() -> details.updateDetails(id, edit))
                .isInstanceOf(NotFoundException.class);

        TenantContextHolder.setTenantId(tenantId);
        assertThat(reread().getFirst().getChequeNumber()).isNull();
    }

    /** And it does the ordinary edit when the tenant is there. */
    @Test
    void updateDetailsWritesTheNumberWhenTheCallIsTenantScoped() {
        UUID id = register.getFirst().getId();

        details.updateDetails(id, new ChequeRowInput(null, null, null, "C-OK",
                LocalDate.of(2026, 3, 1), "Mashreq", null, null, null, null, null));

        assertThat(reread().getFirst().getChequeNumber()).isEqualTo("C-OK");
        assertThat(reread().getFirst().getPayeeBank()).isEqualTo("Mashreq");
    }

    /** The happy path, so the refusals above are refusals of something that otherwise works. */
    @Test
    void aWellFormedBatchWritesEveryRow() {
        List<Cheque> after = tx.execute(s -> {
            details.bulkAttach(leaseId, List.of(
                    item(register.get(0).getId(), "C-1"),
                    item(register.get(1).getId(), "C-2")));
            return chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId);
        });

        assertThat(after).extracting(Cheque::getChequeNumber)
                .containsExactly("C-1", "C-2", null, null);
        assertThat(after).allSatisfy(c -> assertThat(c.getCrtJournalId()).isNull());
    }

    // ------------------------------------------------------------------
    // audit C-F2: only server-issued images
    // ------------------------------------------------------------------

    @Test
    void aPathTheServerNeverIssuedIsRefused() {
        BulkAttachChequeItem it = item(register.get(0).getId(), "C-1");
        it.setImageBlobPath("lease-docs/ab12cd34.pdf");

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(it)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e)).contains("image_not_issued"));
        assertThat(reread().get(0).getImageBlobPath()).isNull();
    }

    @Test
    void anotherTenantsScanIsRefused() {
        BulkAttachChequeItem it = item(register.get(0).getId(), "C-1");
        it.setImageBlobPath(issuedImage(UUID.randomUUID()));

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(it)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e)).contains("image_not_issued"));
    }

    @Test
    void aScanIsClaimedByOneChequeAndTheServerUrlIsStored() {
        BulkAttachChequeItem first = item(register.get(0).getId(), "C-1");
        first.setImageUrl("https://evil.example/phish.jpg");
        details.bulkAttach(leaseId, List.of(first));
        Cheque attached = reread().get(0);
        assertThat(attached.getImageBlobPath()).isEqualTo(first.getImageBlobPath());
        assertThat(attached.getImageUrl()).isEqualTo("https://blob/" + first.getImageBlobPath());

        BulkAttachChequeItem reuse = item(register.get(1).getId(), "C-2");
        reuse.setImageBlobPath(first.getImageBlobPath());
        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(reuse)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e)).contains("image_not_issued"));

        // Re-saving the row with the image it already has is fine.
        BulkAttachChequeItem again = item(register.get(0).getId(), "C-1");
        again.setImageBlobPath(first.getImageBlobPath());
        details.bulkAttach(leaseId, List.of(again));
    }

    @Test
    void oneScanOnTwoRowsOfOneRequestIsRefused() {
        BulkAttachChequeItem a = item(register.get(0).getId(), "C-1");
        BulkAttachChequeItem b = item(register.get(1).getId(), "C-2");
        b.setImageBlobPath(a.getImageBlobPath());

        assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(a, b)))
                .isInstanceOf(BulkAttachValidationException.class)
                .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e))
                        .contains("duplicate_image_in_request"));
    }

    /**
     * Two attaches of one scan at once. Both used to read "unclaimed" before
     * either wrote, so both cheques carried the image and the retention purge
     * later deleted it from under the survivor. The scan row is now locked
     * before the check: here another transaction is mid-claim, the attach waits
     * for it, re-reads a claimed scan and is refused.
     */
    @Test
    void aScanBeingClaimedConcurrentlyIsRefusedOnceTheClaimCommits() throws Exception {
        String path = issuedImage(tenantId);
        UUID holder = register.get(0).getId();
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> claimant = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    tx.executeWithoutResult(s -> {
                        var u = imageUploads.findByTenantIdAndBlobPathForUpdate(tenantId, path).orElseThrow();
                        u.setChequeId(holder);
                        imageUploads.saveAndFlush(u);
                        locked.countDown();
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            BulkAttachChequeItem it = item(register.get(1).getId(), "C-2");
            it.setImageBlobPath(path);
            long start = System.nanoTime();
            assertThatThrownBy(() -> details.bulkAttach(leaseId, List.of(it)))
                    .isInstanceOf(BulkAttachValidationException.class)
                    .satisfies(e -> assertThat(reasons((BulkAttachValidationException) e)).contains("image_not_issued"));
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                    .as("the attach waited for the claim").isGreaterThan(java.time.Duration.ofMillis(500));
            claimant.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reread().get(1).getImageBlobPath()).isNull();
    }

    @jakarta.persistence.PersistenceContext
    jakarta.persistence.EntityManager em;

    /**
     * The scan a cheque holds now is locked up front with the scans being
     * claimed (one statement, blob-path order), not updated blind by the
     * release. When another transaction holds it past the lock timeout, the
     * attach is a 409 that says to retry, not a 500, and nothing is written.
     */
    @Test
    void aHeldScanThatCannotBeLockedIsAConflictAndNothingIsWritten() throws Exception {
        UUID cheque = register.get(0).getId();
        BulkAttachChequeItem first = item(cheque, "C-1");
        details.bulkAttach(leaseId, List.of(first));
        String held = first.getImageBlobPath();

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> holder = pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    tx.executeWithoutResult(s -> {
                        imageUploads.findByTenantIdAndBlobPathForUpdate(tenantId, held).orElseThrow();
                        locked.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            BulkAttachChequeItem replace = item(cheque, "C-1");
            try {
                assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    em.createNativeQuery("set local lock_timeout = '300ms'").executeUpdate();
                    details.bulkAttach(leaseId, List.of(replace));
                }))
                        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                        .satisfies(e -> {
                            var rse = (org.springframework.web.server.ResponseStatusException) e;
                            assertThat(rse.getStatusCode().value()).isEqualTo(409);
                            assertThat(rse.getReason()).isEqualTo(ChequeDetailsService.SCANS_BEING_UPDATED);
                        });
            } finally {
                release.countDown();
            }
            holder.get(10, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(reread().get(0).getImageBlobPath()).isEqualTo(held);
            assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, held).orElseThrow().getChequeId())
                    .isEqualTo(cheque);
            assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, replace.getImageBlobPath()).orElseThrow()
                    .getChequeId()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Replacing a cheque's scan releases the old one: one cheque, one claim (changeset 105). */
    @Test
    void replacingAScanReleasesTheOldClaim() {
        BulkAttachChequeItem first = item(register.get(0).getId(), "C-1");
        details.bulkAttach(leaseId, List.of(first));
        BulkAttachChequeItem second = item(register.get(0).getId(), "C-1");
        details.bulkAttach(leaseId, List.of(second));

        assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, first.getImageBlobPath()).orElseThrow()
                .getChequeId()).isNull();
        assertThat(imageUploads.findByTenantIdAndBlobPath(tenantId, second.getImageBlobPath()).orElseThrow()
                .getChequeId()).isEqualTo(register.get(0).getId());

        // The released scan can now go on another cheque.
        BulkAttachChequeItem reuse = item(register.get(1).getId(), "C-2");
        reuse.setImageBlobPath(first.getImageBlobPath());
        details.bulkAttach(leaseId, List.of(reuse));
        assertThat(reread().get(1).getImageBlobPath()).isEqualTo(first.getImageBlobPath());
    }

    /** The index itself: a second claim by the same cheque is refused by the database. */
    @Test
    void theDatabaseRefusesTwoClaimsByOneCheque() {
        UUID cheque = register.get(0).getId();
        var a = imageUploads.findByTenantIdAndBlobPath(tenantId, issuedImage(tenantId)).orElseThrow();
        var b = imageUploads.findByTenantIdAndBlobPath(tenantId, issuedImage(tenantId)).orElseThrow();
        a.setChequeId(cheque);
        imageUploads.saveAndFlush(a);
        b.setChequeId(cheque);
        assertThatThrownBy(() -> imageUploads.saveAndFlush(b))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
