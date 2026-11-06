package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseExpirationJob;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import com.datagami.rentaxis.testsupport.LockedBooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holdover and back-dated renewals — the client call scenario from the November 2026
 * call. A contract ended on 30 Sep 2025 and was never renewed; the renter could not
 * be reached but stayed. On 13 Nov 2026, with the books closed through October, the
 * renter comes back and the landlord renews twice: 2025-26 (entirely in the past)
 * and 2026-27 (started in October).
 *
 * <p><b>The golden rule:</b> nothing on either renewal is dated before November
 * 2026. The months already passed are recognised in one catch-up on the posting
 * date, and the month-end run carries on from November.</p>
 */
@SpringBootTest
@Import(HoldoverIT.FixedClockConfig.class)
class HoldoverIT extends AbstractPostgresIT {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    static final LocalDate INCOME_FROM = LocalDate.of(2026, 11, 1);
    static final LocalDate LOCKED_THROUGH = LocalDate.of(2026, 10, 31);

    static final LocalDate OLD_CONTRACT = LocalDate.of(2024, 9, 15);
    static final LocalDate OLD_START = LocalDate.of(2024, 10, 1);
    static final LocalDate OLD_END = LocalDate.of(2025, 9, 30);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).plusHours(3).toInstant(), ZoneOffset.UTC);
        }
    }

    @Autowired LeaseExpirationJob expiry;
    @Autowired LeaseService leaseService;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired HoldoverService holdover;
    @Autowired BackdatedIncome backdated;
    @Autowired RecognitionService recognition;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures f;
    private UUID oldLease;

    @BeforeEach
    void setUp() {
        f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
        oldLease = f.postedLease(OLD_CONTRACT, OLD_START, OLD_END, List.of(line("RENT", "60000")), 4, null)
                .lease().getId();
        // Every month the old contract earned is on the books, then October 2026 is closed.
        recognition.runTo(LOCKED_THROUGH, false);
        LockedBooks.lockOverPlanned(jdbc, LOCKED_THROUGH);
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        expiry.runFor(TODAY);
        TenantContextHolder.setTenantId(f.tenantId());
        LeaseTestFixtures.authenticateAsTenantAdmin();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void aTermThatRanOutWithNoRenewalGoesOnHoldAndKeepsTheUnit() {
        assertThat(lease(oldLease).getStatus()).isEqualTo(LeaseStatus.ON_HOLD);
        assertThat(unitRepo.findById(f.unit().getId()).orElseThrow().getStatus()).isEqualTo(UnitStatus.OCCUPIED);

        Renter stranger = f.createRenter("Someone Else");
        assertThatThrownBy(() -> f.draftLease(f.unit(), stranger, TODAY, TODAY, TODAY.plusYears(1).minusDays(1),
                List.of(line("RENT", "70000"))))
                .as("an occupied flat on hold cannot be let to anyone else")
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void aRenewalOfAHoldoverMustStartTheDayAfterTheOldEnd() {
        assertThatThrownBy(() -> renewal.renew(oldLease, new RenewLeaseRequest(TODAY, LocalDate.of(2026, 11, 1),
                LocalDate.of(2027, 10, 31), null, false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must start on 01/10/2025");
    }

    /**
     * The golden test. Two back-dated renewals posted on 13 Nov 2026 with income from
     * November: no journal on either is dated before 1 Nov, the passed months are one
     * catch-up on the posting date, and November onwards is left to the month end.
     */
    @Test
    void backDatedRenewalsPutNothingBeforeTheIncomeMonth() {
        // 2025-26: wholly in the past.
        UUID first = renewal.renew(oldLease, new RenewLeaseRequest(TODAY, OLD_END.plusDays(1),
                LocalDate.of(2026, 9, 30), null, false)).getId();
        f.generateGrid(first, 4, OLD_END.plusDays(1));

        BackdatedIncome.Options offered = backdated.options(first, null);
        assertThat(offered.backdated()).isTrue();
        assertThat(offered.defaultFrom()).isEqualTo(INCOME_FROM);
        assertThat(offered.earliest()).as("the closed months are not offered").isEqualTo(INCOME_FROM);
        assertThat(offered.foldedAmount()).isEqualByComparingTo("60000.00");

        posting.post(first, null, INCOME_FROM);

        assertThat(lease(oldLease).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(lease(first).getIncomeFromDate()).isEqualTo(INCOME_FROM);
        assertNothingDatedBefore(first, INCOME_FROM);
        assertThat(catchUpTotal(first, TODAY)).as("the whole past contract, recognised today")
                .isEqualByComparingTo("60000.00");
        assertThat(plannedEndingBefore(first, INCOME_FROM)).isZero();

        // Its term is over too: the sweep puts it on hold, and the 2026-27 renewal follows.
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
        expiry.runFor(TODAY);
        TenantContextHolder.setTenantId(f.tenantId());
        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(lease(first).getStatus()).isEqualTo(LeaseStatus.ON_HOLD);

        UUID second = renewal.renew(first, new RenewLeaseRequest(TODAY, LocalDate.of(2026, 10, 1),
                LocalDate.of(2027, 9, 30), null, false)).getId();
        f.generateGrid(second, 4, LocalDate.of(2026, 10, 1));
        posting.post(second, null, INCOME_FROM);

        assertNothingDatedBefore(second, INCOME_FROM);
        // October 2026 at 60,000 over 365 days: 31 × 164.383562 = 5,095.89.
        assertThat(catchUpTotal(second, TODAY)).isEqualByComparingTo("5095.89");
        assertThat(plannedEndingBefore(second, INCOME_FROM)).isZero();

        // The month end recognises November in November, as for any contract.
        recognition.runTo(LocalDate.of(2026, 11, 30), false);
        assertNothingDatedBefore(second, INCOME_FROM);
        assertThat(jdbc.queryForObject("""
                select count(*) from recognition_entries r join journal_entries j on j.id = r.journal_id
                where r.lease_id = ? and r.status = 'POSTED' and r.period_start = date '2026-11-01'
                  and j.entry_date = date '2026-11-30'""", Long.class, second)).isEqualTo(1);
    }

    @Test
    void theIncomeMonthCannotBeAClosedMonthOrLaterThanTheContract() {
        UUID first = renewal.renew(oldLease, new RenewLeaseRequest(TODAY, OLD_END.plusDays(1),
                LocalDate.of(2026, 9, 30), null, false)).getId();
        f.generateGrid(first, 4, OLD_END.plusDays(1));

        assertThatThrownBy(() -> posting.post(first, null, LocalDate.of(2026, 10, 1)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("closed");
        assertThatThrownBy(() -> posting.post(first, null, LocalDate.of(2026, 12, 1)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("month of the contract date");
        assertThat(lease(first).getStatus()).as("nothing was posted").isEqualTo(LeaseStatus.DRAFT);
    }

    /**
     * The renter left on 15 Nov 2025: 46 days at 60,000 / 365 = 7,561.64, charged
     * as use and occupation today; the lease expires and the flat is released.
     */
    @Test
    void aRenterWhoLeftIsChargedUseAndOccupationAndTheUnitIsReleased() {
        LocalDate movedOut = LocalDate.of(2025, 11, 15);
        HoldoverService.VacatePreview p = holdover.previewVacate(oldLease, movedOut);
        assertThat(p.days()).isEqualTo(46);
        assertThat(p.amount()).isEqualByComparingTo("7561.64");
        assertThat(p.months()).extracting(HoldoverService.Month::month).containsExactly("10/2025", "11/2025");
        assertThat(p.months().stream().map(HoldoverService.Month::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(p.amount());

        HoldoverService.VacateResult r = holdover.vacate(oldLease, new HoldoverService.VacateRequest(movedOut, p.amount()));

        assertThat(r.chargeId()).isNotNull();
        assertThat(lease(oldLease).getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(unitRepo.findById(f.unit().getId()).orElseThrow().getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(jdbc.queryForObject("""
                select sum(l.credit) from journal_entries j join journal_lines l on l.journal_entry_id = j.id
                where j.lease_id = ? and j.doc_type = 'PEN' and j.entry_date = ?""",
                BigDecimal.class, oldLease, TODAY)).isEqualByComparingTo("7561.64");
    }

    // ------------------------------------------------------------------

    private Lease lease(UUID id) {
        return tx.execute(s -> leaseRepo.findById(id).orElseThrow());
    }

    private void assertNothingDatedBefore(UUID leaseId, LocalDate day) {
        assertThat(jdbc.queryForList("select entry_number || ' ' || doc_type || ' ' || entry_date from journal_entries"
                        + " where lease_id = ? and entry_date < ?", String.class, leaseId, day))
                .as("journals on the renewal dated before " + day).isEmpty();
    }

    private BigDecimal catchUpTotal(UUID leaseId, LocalDate on) {
        return jdbc.queryForObject("""
                select coalesce(sum(r.amount), 0) from recognition_entries r join journal_entries j on j.id = r.journal_id
                where r.lease_id = ? and r.status = 'POSTED' and j.doc_type = 'CIL' and j.entry_date = ?""",
                BigDecimal.class, leaseId, on);
    }

    private long plannedEndingBefore(UUID leaseId, LocalDate day) {
        return jdbc.queryForObject("select count(*) from recognition_entries where lease_id = ? and status = 'PLANNED'"
                + " and period_end < ?", Long.class, leaseId, day);
    }
}
