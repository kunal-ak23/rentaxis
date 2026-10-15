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
import com.datagami.rentaxis.testsupport.LockedBooks;
import com.datagami.rentaxis.core.service.recognition.LockedMonthsCatchUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR #400 review P2-1: books already locked over PLANNED recognition (a lock set
 * before the lock refused that — bug 46's state) must not be stuck. The lock forward
 * counts only what the move newly covers, and the locked-months catch-up recognises
 * the stranded rows as one CIL per lease and account pair, dated the first open day.
 */
@SpringBootTest
class LockedMonthsCatchUpIT extends AbstractPostgresIT {

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
    @Autowired com.datagami.rentaxis.core.service.recognition.RecognitionService recognition;
    @Autowired com.datagami.rentaxis.core.service.recognition.LockedMonthsCatchUp catchUp;

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

    private long planned(String status, LocalDate through) {
        return jdbc.queryForObject("select count(*) from recognition_entries where tenant_id = ? and status = ?"
                + " and period_end <= ?", Long.class, fixtures.tenantId(), status, through);
    }

    @Test
    void aLockOverPlannedMonthsIsNotStuckAndTheCatchUpPostsThemOnTheFirstOpenDay() {
        tx.executeWithoutResult(st -> fiscal.setBooksStartDate(LocalDate.of(2026, 5, 1)));
        LocalDate start = LocalDate.of(2026, 6, 1);
        UUID leaseId = fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(line("RENT", "60000")), 2,
                LeaseTestFixtures.nextChequeBook()).lease().getId();
        tx.executeWithoutResult(st -> recognition.runTo(LocalDate.of(2026, 7, 31), false));
        // Bug 46's state: locked through 30/09 while August and September are PLANNED.
        LockedBooks.lockOverPlanned(jdbc, LocalDate.of(2026, 9, 30));
        assertThat(planned("PLANNED", LocalDate.of(2026, 9, 30))).isEqualTo(2);
        BigDecimal stranded = jdbc.queryForObject("select sum(amount) from recognition_entries where tenant_id = ?"
                + " and status = 'PLANNED' and period_end <= '2026-09-30'", BigDecimal.class, fixtures.tenantId());

        // (a) The forward move names only what it newly covers (October), not the stranded months.
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> fiscal.lockThrough(LocalDate.of(2026, 10, 31))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("fiscal.recognitionPendingForLock");
                    assertThat(e.getArgs()).containsEntry("months", "10/2026").containsEntry("count", "1");
                });

        // A preview writes nothing.
        LockedMonthsCatchUp.Result preview = catchUp.run(LocalDate.of(2026, 11, 5), true);
        assertThat(preview.months()).isEqualTo(2);
        assertThat(preview.amount()).isEqualByComparingTo(stranded);
        assertThat(planned("PLANNED", LocalDate.of(2026, 9, 30))).isEqualTo(2);

        // (b) The catch-up: one CIL, dated the first day after the lock, naming the months.
        LockedMonthsCatchUp.Result done = catchUp.run(LocalDate.of(2026, 11, 5), false);
        assertThat(done.errors()).isEmpty();
        assertThat(done.leases()).isEqualTo(1);
        assertThat(done.months()).isEqualTo(2);
        assertThat(done.amount()).isEqualByComparingTo(stranded);
        assertThat(done.postedOn()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(done.monthsNamed()).containsExactly("08/2026", "09/2026");
        assertThat(planned("PLANNED", LocalDate.of(2026, 9, 30))).isZero();
        assertThat(planned("CANCELLED", LocalDate.of(2026, 9, 30))).isEqualTo(2);

        List<java.util.Map<String, Object>> cil = jdbc.queryForList("""
                select je.id, je.entry_date, je.narration from journal_entries je
                where je.tenant_id = ? and je.doc_type = 'CIL' and je.narration like '%locked months never recognised%'""",
                fixtures.tenantId());
        assertThat(cil).hasSize(1);
        assertThat(cil.get(0).get("entry_date").toString()).isEqualTo("2026-10-01");
        assertThat((String) cil.get(0).get("narration")).contains("08/2026, 09/2026");
        // Never inside the lock, and the lock did not move.
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 9, 30));

        // Per property: both legs on the lease's property, balanced, for the stranded amount.
        java.util.Map<String, Object> legs = jdbc.queryForMap("""
                select sum(debit) dr, sum(credit) cr, count(distinct property_id) props, min(property_id::text) prop
                from journal_lines where journal_entry_id = ?""", cil.get(0).get("id"));
        assertThat((BigDecimal) legs.get("dr")).isEqualByComparingTo(stranded);
        assertThat((BigDecimal) legs.get("cr")).isEqualByComparingTo(stranded);
        assertThat(((Number) legs.get("props")).intValue()).isEqualTo(1);
        assertThat(legs.get("prop")).isEqualTo(fixtures.property().getId().toString());

        // Nothing stranded now; a second catch-up does nothing.
        assertThat(catchUp.run(LocalDate.of(2026, 11, 5), false).months()).isZero();
        TenantContextHolder.setTenantId(fixtures.tenantId());
        assertThat(recognition.lockedUnrecognisedAll().months()).isZero();
        // The schedule still sums to the contract: posted rows (catch-up included) + planned rows.
        BigDecimal live = jdbc.queryForObject("select sum(amount) from recognition_entries where lease_id = ?"
                + " and status in ('POSTED','PLANNED')", BigDecimal.class, leaseId);
        assertThat(live).isEqualByComparingTo("60000");

        // Then the ordinary close, and the lock moves forward.
        tx.executeWithoutResult(st -> recognition.runTo(LocalDate.of(2026, 10, 31), false));
        tx.executeWithoutResult(st -> fiscal.lockThrough(LocalDate.of(2026, 10, 31)));
        assertThat(lock()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    void theCatchUpWaitsWhileTheFirstOpenDayIsStillAhead() {
        tx.executeWithoutResult(st -> fiscal.setBooksStartDate(LocalDate.of(2026, 5, 1)));
        LocalDate start = LocalDate.of(2026, 6, 1);
        fixtures.postedLease(start, start, start.plusYears(1).minusDays(1), List.of(line("RENT", "60000")), 2,
                LeaseTestFixtures.nextChequeBook());
        LockedBooks.lockOverPlanned(jdbc, LocalDate.of(2026, 9, 30));
        LockedMonthsCatchUp.Result r = catchUp.run(LocalDate.of(2026, 9, 30), false);
        assertThat(r.months()).isZero();
        assertThat(planned("PLANNED", LocalDate.of(2026, 9, 30))).isEqualTo(4);
    }
}
