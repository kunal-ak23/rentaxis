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
        // Unnumbered on purpose: the numbers are what this test is about.
        leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, null).lease().getId();
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
        it.setImageBlobPath("t/x.jpg");
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
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
}
