package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseVariationService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #326: {@code DELETE /api/admin/tenants/{id}} 500d as soon as the tenant
 * owned a POSTED lease.
 *
 * <p>Two things stand between the cascade and a ledger: the {@code leases} ↔
 * {@code journal_entries} foreign-key cycle (the lease points at its posting
 * journal, the journal carries the lease dimension), which no ordering of
 * tenant-scoped DELETEs can resolve; and the immutability triggers from
 * changeset 81, which refuse a DELETE on {@code journal_entries} and
 * {@code journal_lines} outright. The retry loop therefore made no forward
 * progress and bailed with "deleteTenant stalled after pass 1".</p>
 *
 * <p>The assertion is deliberately "no rows left anywhere", not just "no
 * exception": a purge that swallowed the ledger tables and left their rows
 * behind would orphan journals against a landlord_org that no longer exists.</p>
 */
@SpringBootTest
class TenantDeletionPostedLeaseIT extends AbstractPostgresIT {

    @Autowired LandlordOrgService service;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;
    @Autowired LeaseVariationService variations;
    @Autowired ChequeService cheques;
    @Autowired PenaltyAssessmentService penalties;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    /** The post-commit document sweep talks to disk/Azure; nothing here is about that. */
    @MockitoBean ContractGenerationService contractGenerationService;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate DEPOSITED_ON = LocalDate.of(2026, 10, 5);
    private static final LocalDate BOUNCED_ON = LocalDate.of(2026, 10, 12);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Autowired MaintenanceTicketService ticketService;

    /**
     * Changeset 99 put a foreign key (ON DELETE SET NULL) on a ticket's
     * on-behalf renter. The purge deletes table by table in whatever order the
     * foreign keys allow; a SET NULL must never be what stalls it.
     */
    @Test
    void deletesATenantWithATicketLoggedOnARentersBehalf() {
        com.datagami.rentaxis.api.dto.CreateTicketDTO dto = new com.datagami.rentaxis.api.dto.CreateTicketDTO();
        dto.setPropertyId(fixtures.property().getId());
        dto.setTitle("Noise from 1204");
        dto.setOnBehalfOfRenterId(fixtures.renter().getId());
        ticketService.createTicket(dto, UUID.randomUUID());

        UUID tenantId = fixtures.tenantId();
        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();
        assertThat(rows("maintenance_tickets", tenantId)).isEqualTo(1);
        TenantContextHolder.clear();

        service.deleteTenant(tenantId, org.getName());

        assertThat(orgRepo.findById(tenantId)).isEmpty();
        assertThat(rows("maintenance_tickets", tenantId)).isZero();
        assertThat(rows("renters", tenantId)).isZero();
    }

    @Test
    void deletesATenantWhoseLeaseIsPosted() {
        PostLeaseResponse posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        assertThat(posted.tcoJournalId()).isNotNull();

        UUID tenantId = fixtures.tenantId();
        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();

        // Sanity: the ledger this delete has to get past really exists.
        assertThat(rows("journal_entries", tenantId)).isPositive();
        assertThat(rows("journal_lines", tenantId)).isPositive();
        assertThat(rows("cheques", tenantId)).isPositive();
        assertThat(rows("leases", tenantId)).isPositive();

        // The super admin who presses Delete carries no tenant of their own.
        TenantContextHolder.clear();

        service.deleteTenant(tenantId, org.getName());

        assertThat(orgRepo.findById(tenantId)).isEmpty();
        for (String table : List.of("journal_entries", "journal_lines", "leases", "cheques",
                "lease_lines", "rent_segments", "recognition_entries", "properties", "units")) {
            assertThat(rows(table, tenantId)).as("rows surviving in %s", table).isZero();
        }

        // The triggers themselves are never touched — no DDL, no table lock — so
        // every one of them is still enabled and still guarding.
        assertThat(triggersEnabled("journal_entries")).isTrue();
        assertThat(triggersEnabled("journal_lines")).isTrue();
    }

    /**
     * The tenant the production Playwright suite builds and then deletes at
     * {@code 99-cleanup} — which still answered 500 after the ledger was dealt
     * with: {@code deleteTenant stalled after pass 4 with tables: [users,
     * properties, units, renters, accounts, leases, cheques,
     * penalty_assessments]}.
     *
     * <p>One foreign-key cycle accounts for all eight:
     * {@code cheques.penalty_assessment_id} points at {@code penalty_assessments}
     * and {@code penalty_assessments.cheque_id} / {@code collection_cheque_id}
     * point back, so neither table can ever be deleted first — and {@code leases},
     * {@code renters}, {@code units}, {@code accounts} and {@code properties} are
     * all blocked behind {@code cheques}, with {@code users} behind
     * {@code renters}. The lease-only fixture above never produced a penalty, so
     * it never met the cycle.</p>
     */
    @Test
    void deletesATenantWithPenaltiesUsersAndAFullRegister() {
        PostLeaseResponse posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        UUID leaseId = posted.lease().getId();
        UUID tenantId = fixtures.tenantId();

        // A fine, proposed off a returned cheque and approved: the assessment
        // points at the bounced cheque, and its collection row points back at the
        // assessment.
        UUID bounced = posted.cheques().get(0).id();
        cheques.deposit(bounced, ChequeActionRequest.on(DEPOSITED_ON));
        cheques.bounce(bounced, ChequeActionRequest.on(BOUNCED_ON));
        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, bounced, PenaltyReason.CHEQUE_RETURN, new BigDecimal("500"),
                "Returned cheque fee"), UUID.randomUUID());
        penalties.approve(proposed.id(), BOUNCED_ON);

        // Staff of their own: a property manager beside the renter's user row.
        User manager = new User();
        manager.setEmail("manager+" + UUID.randomUUID() + "@test");
        manager.setName("Property Manager");
        manager.setRole(UserRole.PROPERTY_MANAGER);
        manager.setStatus(UserStatus.ACTIVE);
        manager.setPasswordHash("x");
        manager.setTenantId(tenantId);
        userRepo.save(manager);

        assertThat(rows("penalty_assessments", tenantId)).isPositive();
        assertThat(rows("accounts", tenantId)).isPositive();
        assertThat(rows("users", tenantId)).isGreaterThan(1);
        assertThat(rows("renters", tenantId)).isPositive();

        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();
        TenantContextHolder.clear();

        service.deleteTenant(tenantId, org.getName());

        assertThat(orgRepo.findById(tenantId)).isEmpty();
        for (String table : List.of("journal_entries", "journal_lines", "leases", "cheques",
                "penalty_assessments", "accounts", "users", "renters", "properties", "units",
                "lease_lines", "rent_segments", "recognition_entries", "lease_events")) {
            assertThat(rows(table, tenantId)).as("rows surviving in %s", table).isZero();
        }
    }

    /**
     * A lease that took an addendum. {@code lease_addenda.tco_journal_id} points at
     * the addendum's TCO, so {@code purgeLedger} NULLs it with every other
     * tenant-scoped journal pointer before the journal goes — which a NOT NULL
     * column refuses (23502), rolling the whole delete back.
     */
    @Test
    void deletesATenantWhoseLeaseTookAnAddendum() {
        PostLeaseResponse posted = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
        UUID leaseId = posted.lease().getId();
        UUID tenantId = fixtures.tenantId();

        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2027, 2, 15), LocalDate.of(2027, 2, 10),
                null, "Parking bay P-12", List.of(line("PARKING_FEE", "6000")),
                List.of(chequeRow("6000", LocalDate.of(2027, 3, 1)))));
        assertThat(rows("lease_addenda", tenantId)).isEqualTo(1);

        LandlordOrg org = orgRepo.findById(tenantId).orElseThrow();
        TenantContextHolder.clear();

        service.deleteTenant(tenantId, org.getName());

        assertThat(orgRepo.findById(tenantId)).isEmpty();
        for (String table : List.of("lease_addenda", "journal_entries", "journal_lines", "leases",
                "cheques", "lease_lines", "rent_segments")) {
            assertThat(rows(table, tenantId)).as("rows surviving in %s", table).isZero();
        }
    }

    /**
     * The exemption is one tenant's, for one transaction — and it costs nobody
     * else their ledger.
     *
     * <p>The shape this replaced switched the append-only triggers off with
     * {@code ALTER TABLE … DISABLE TRIGGER}, whose ACCESS EXCLUSIVE lock Postgres
     * holds until <em>commit</em>: every other tenant's postings and reads would
     * have queued behind the delete for the whole of it. So this opens a purge
     * transaction, leaves it open, and from another thread posts a second lease
     * for a different tenant — which must complete rather than block — while
     * checking that the same transaction is still refused the other tenant's
     * journal rows.</p>
     */
    @Test
    void thePurgeExemptionIsOneTenantsAndBlocksNobodyElse() {
        fixtures.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")), 4, "100040");
        UUID purged = fixtures.tenantId();

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        UUID otherTenant = other.tenantId();
        other.postedLease(CONTRACT_DATE, START, END, List.of(line("RENT", "24000")), 2, "200010");
        // Drafted now, posted from the other thread while the purge is open.
        UUID toPostConcurrently = other.draftLease(
                other.createUnit(other.property(), "202"), other.createRenter("Second Renter"),
                CONTRACT_DATE, START, END, List.of(line("RENT", "12000")));
        generation.generate(toPostConcurrently, new GenerateChequesRequest(
                1, START, null, "Emirates NBD", null, false, null));
        generation.generateNumbers(toPostConcurrently, LeaseTestFixtures.nextChequeBook());

        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();

        ExecutorService elsewhere = Executors.newSingleThreadExecutor();
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT set_config('rentaxis.purging_tenant', ?, true)",
                        String.class, purged.toString());

                // Exactly what the purge does, on this tenant's rows.
                int deleted = jdbc.update("DELETE FROM journal_lines WHERE tenant_id = ?", purged);
                assertThat(deleted).as("the exemption admits this tenant's own lines").isPositive();

                // …and only on this tenant's rows.
                assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM journal_lines WHERE tenant_id = ?", otherTenant))
                        .rootCause()
                        .hasMessageContaining("journal lines are immutable");

                // Another tenant posts to the ledger meanwhile. It must not wait for
                // this transaction: with a table-level lock held it never returns.
                Future<?> posted = elsewhere.submit(() -> {
                    TenantContextHolder.setTenantId(otherTenant);
                    LeaseTestFixtures.authenticateAsTenantAdmin();
                    try {
                        return posting.post(toPostConcurrently);
                    } finally {
                        TenantContextHolder.clear();
                        LeaseTestFixtures.clearAuth();
                    }
                });
                try {
                    posted.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("A posting for another tenant was blocked by the purge", e);
                }

                throw new RollbackMarker();
            })).isInstanceOf(RollbackMarker.class);
        } finally {
            elsewhere.shutdownNow();
        }

        // Rolled back: the purged tenant's ledger is untouched, and the other
        // tenant's posting stands on its own commit.
        assertThat(rows("journal_lines", purged)).isPositive();
        assertThat(rows("journal_entries", otherTenant)).isPositive();
    }

    /** Rolls the probe transaction back without pretending anything failed. */
    private static final class RollbackMarker extends RuntimeException {
    }

    private long rows(String table, UUID tenantId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM \"" + table + "\" WHERE tenant_id = ?",
                Long.class, tenantId);
    }

    /** Every user trigger on the table is enabled ('O' = origin, i.e. on). */
    private boolean triggersEnabled(String table) {
        Long disabled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid " +
                        "WHERE c.relname = ? AND NOT t.tgisinternal AND t.tgenabled = 'D'",
                Long.class, table);
        return disabled != null && disabled == 0;
    }
}
