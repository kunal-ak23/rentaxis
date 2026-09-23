package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The poster's lock has to hand back the row's <em>committed</em> status, not the
 * one its caller already has in memory.
 *
 * <p>{@code postJoining} runs in the caller's transaction, and its one production
 * caller — a termination re-cutting the month that contains the termination date —
 * has already loaded that entry to work out what the cut is worth. A {@code @Lock}
 * query in that situation still issues {@code SELECT … FOR UPDATE}, but Hibernate
 * answers from the first-level cache and returns the instance it loaded before:
 * the lock is taken and the status read is stale, which is precisely the value the
 * lock exists to stop us acting on. The consequence is a second {@code CIL} for a
 * period that already has one.</p>
 *
 * <p>So the test loads the entry first, commits a change to it from another
 * transaction, and asks the poster to post. It must see the new status.</p>
 */
@SpringBootTest
class RecognitionPosterLockIT extends AbstractPostgresIT {

    @Autowired RecognitionPoster poster;
    @Autowired RecognitionEntryRepository entries;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2027, 9, 30);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /**
     * The entry is loaded, then cancelled from elsewhere, then posted. The poster
     * refuses it.
     *
     * <p>With a plain locking finder this passes the status check on the stale
     * PLANNED instance, posts a {@code CIL}, and commits POSTED over a row somebody
     * else had cancelled — a lost update that leaves recognised income no schedule
     * row explains.</p>
     */
    @Test
    void theLockRefreshesAStaleInstanceSoACancelledEntryCannotPost() {
        UUID entryId = firstPlannedEntry();
        long cilsBefore = cilCount();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // The caller's transaction already holds this row — the shape a
            // termination is in when it calls postJoining.
            RecognitionEntry loaded = entries.findById(entryId).orElseThrow();
            assertThat(loaded.getStatus()).isEqualTo(RecognitionStatus.PLANNED);

            cancelInAnotherTransaction(entryId);

            poster.postJoining(entryId);
        }))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is already CANCELLED");

        assertThat(cilCount()).as("no journal was written").isEqualTo(cilsBefore);
        assertThat(statusOf(entryId)).isEqualTo(RecognitionStatus.CANCELLED);
        assertThat(journalIdOf(entryId)).isNull();
    }

    /** …and an entry nobody touched still posts, so the refresh has not broken the happy path. */
    @Test
    void anUntouchedEntryStillPosts() {
        UUID entryId = firstPlannedEntry();
        long cilsBefore = cilCount();

        tx.executeWithoutResult(status -> {
            entries.findById(entryId).orElseThrow();
            poster.postJoining(entryId);
        });

        assertThat(statusOf(entryId)).isEqualTo(RecognitionStatus.POSTED);
        assertThat(journalIdOf(entryId)).isNotNull();
        assertThat(cilCount()).isEqualTo(cilsBefore + 1);
    }

    // ------------------------------------------------------------------

    /** A posted lease writes its whole schedule as PLANNED; the first row is October's. */
    private UUID firstPlannedEntry() {
        UUID leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000")), 4, null).lease().getId();
        return tx.execute(s -> entries.findByLease_IdOrderByPeriodStartAsc(leaseId).stream()
                .filter(e -> e.getStatus() == RecognitionStatus.PLANNED)
                .findFirst().orElseThrow().getId());
    }

    /**
     * Commits a status change on a genuinely separate transaction.
     *
     * <p>{@code REQUIRES_NEW} suspends the caller's transaction and takes its own
     * connection, so the update is committed and visible before the poster reads —
     * which is the situation a nightly close creates. The caller holds no lock on
     * the row (it only read it), so this cannot deadlock.</p>
     */
    private void cancelInAnotherTransaction(UUID entryId) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        separate.executeWithoutResult(s ->
                jdbc.update("update recognition_entries set status = 'CANCELLED' where id = ?", entryId));
    }

    private RecognitionStatus statusOf(UUID entryId) {
        return RecognitionStatus.valueOf(jdbc.queryForObject(
                "select status from recognition_entries where id = ?", String.class, entryId));
    }

    private UUID journalIdOf(UUID entryId) {
        return jdbc.queryForObject("select journal_id from recognition_entries where id = ?", UUID.class, entryId);
    }

    private long cilCount() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = 'CIL'",
                Long.class, fixtures.tenantId());
    }
}
