package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review of R4-B, I3: a lock the books start set follows the books start. Moving the
 * books start forward on an organisation with journals used to write the new lock
 * straight onto the row — no row lock, and no refusal while a PLANNED recognition
 * entry or VAT tax point fell inside it, which {@code lockThrough} and the year-end
 * close refuse. The forward move now takes the row lock and refuses such a lock;
 * the backward move still follows the books start back.
 */
@SpringBootTest
class BooksStartForwardMoveIT extends AbstractPostgresIT {

    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LocalDate lock() {
        return jdbc.queryForObject("select books_locked_through from tenant_fiscal_settings where tenant_id = ?",
                LocalDate.class, fixtures.tenantId());
    }

    private LocalDate booksStart() {
        return jdbc.queryForObject("select books_start_date from tenant_fiscal_settings where tenant_id = ?",
                LocalDate.class, fixtures.tenantId());
    }

    private void setBooksStart(LocalDate d) {
        tx.executeWithoutResult(s -> fiscal.setBooksStartDate(d));
    }

    @Test
    void aForwardMoveOverPlannedRecognitionIsRefusedAndABackwardMoveStillFollows() {
        setBooksStart(LocalDate.of(2026, 5, 1));
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 4, 30));

        // Journals now exist, and June's and July's rent are PLANNED recognition.
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(line("RENT", "60000")), 2,
                LeaseTestFixtures.nextChequeBook());

        assertThatThrownBy(() -> setBooksStart(LocalDate.of(2026, 8, 1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("fiscal.recognitionPendingInLock");
                    assertThat(e.getArgs()).containsEntry("through", "31/07/2026");
                });
        assertThat(lock()).as("nothing moved").isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(booksStart()).isEqualTo(LocalDate.of(2026, 5, 1));

        // Forward over nothing planned is still allowed (the first rent period ends 30/06).
        setBooksStart(LocalDate.of(2026, 5, 20));
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 5, 19));

        // And back: the books start's own lock follows it back, journals or not.
        setBooksStart(LocalDate.of(2026, 3, 1));
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void aForwardMoveOverAPlannedVatTaxPointIsRefused() {
        setBooksStart(LocalDate.of(2026, 5, 1));
        // A VAT contract: its first instalment's tax point (01/06) is PLANNED.
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(vatLine("RENT", "60000")), 4,
                LeaseTestFixtures.nextChequeBook());

        assertThatThrownBy(() -> setBooksStart(LocalDate.of(2026, 8, 1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("fiscal.vatPendingInLock");
                    assertThat(e.getArgs()).containsEntry("date", "01/06/2026");
                });
        assertThat(lock()).as("nothing moved").isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(booksStart()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    // ------------------------------------------------------------------
    // Review of PR #392, I1: the FIRST books start on an organisation with journals
    // writes a lock too, and a backward move must not overwrite a user's lock.
    // ------------------------------------------------------------------

    @Test
    void aFirstBooksStartOverAPlannedVatTaxPointIsRefused() {
        assertThat(lock()).as("no books start, no lock").isNull();
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(vatLine("RENT", "60000")), 4,
                LeaseTestFixtures.nextChequeBook());

        assertThatThrownBy(() -> setBooksStart(LocalDate.of(2026, 8, 1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("fiscal.vatPendingInLock");
                    assertThat(e.getArgs()).containsEntry("date", "01/06/2026");
                });
        assertThat(lock()).as("nothing written").isNull();
        assertThat(booksStart()).isNull();
    }

    @Test
    void aFirstBooksStartOverPlannedRecognitionIsRefused() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(line("RENT", "60000")), 2,
                LeaseTestFixtures.nextChequeBook());

        assertThatThrownBy(() -> setBooksStart(LocalDate.of(2026, 8, 1)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("fiscal.recognitionPendingInLock"));
        assertThat(lock()).isNull();
    }

    @Test
    void aFirstBooksStartOverNothingPlannedIsAccepted() {
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(vatLine("RENT", "60000")), 4,
                LeaseTestFixtures.nextChequeBook());

        // The first tax point is 01/06 and the first rent period ends 30/06: both after 19/05.
        setBooksStart(LocalDate.of(2026, 5, 20));
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 5, 19));
        assertThat(booksStart()).isEqualTo(LocalDate.of(2026, 5, 20));
    }

    /**
     * A backward books-start move racing a user's lockThrough: the user's lock commits
     * while the move waits for the settings row, and the move then sees it is no longer
     * the books start's lock and keeps it. Deterministic: this thread holds the row
     * (lockThrough's FOR UPDATE) until the move is parked on it.
     */
    @Test
    void aBackwardMoveAfterAUsersLockCommittedKeepsTheUsersLock() throws Exception {
        setBooksStart(LocalDate.of(2026, 5, 1));
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(line("RENT", "60000")), 2,
                LeaseTestFixtures.nextChequeBook());
        java.util.UUID tenantId = fixtures.tenantId();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch rowLocked = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> moving = pool.submit(() -> {
                assertThat(rowLocked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                TenantContextHolder.setTenantId(tenantId);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    setBooksStart(LocalDate.of(2026, 3, 1));
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
                return null;
            });
            tx.executeWithoutResult(st -> {
                fiscal.lockThrough(LocalDate.of(2026, 5, 31));
                rowLocked.countDown();
                awaitABlockedBackend();
            });
            moving.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(lock()).as("the user's lock stands").isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(booksStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(jdbc.queryForObject("select books_lock_from_start from tenant_fiscal_settings where tenant_id = ?",
                Boolean.class, tenantId)).isFalse();
    }

    /** Wait until some backend is parked on a row lock, rather than sleeping and hoping. */
    private void awaitABlockedBackend() {
        for (int i = 0; i < 300; i++) {
            Long waiting = jdbc.queryForObject("select count(*) from pg_stat_activity"
                    + " where datname = current_database() and wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("No backend ever blocked on the row lock");
    }
}
