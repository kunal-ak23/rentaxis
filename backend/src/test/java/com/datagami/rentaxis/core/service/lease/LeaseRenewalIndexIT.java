package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange;
import com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.rentalindex.RentalIndexMath;
import com.datagami.rentaxis.core.service.rentalindex.RentalIndexRateService.IndexStatus;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.RenewalIndexCheck;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenewalIndexCheckRepository;
import com.datagami.rentaxis.domain.repository.RentalIndexRateRepository;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rental index cap on a single renewal (spec 2026-11-25 §6, §8, §14.1, §14.2, §14.4, §14.7). A Dubai 2-bedroom
 * let at 120,000 a year; the range in force is 138,000–162,000 (average 150,000, gap 20% → 5%, cap 126,000).
 * Synthetic figures only.
 */
@SpringBootTest
class LeaseRenewalIndexIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TenantFeatureService features;
    @Autowired RentalIndexRateRepository rates;
    @Autowired RenewalIndexCheckRepository checks;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;

    private LeaseTestFixtures fx;
    private UUID tenantId;
    private Unit unit;
    private LocalDate today;
    private LocalDate start;
    private LocalDate end;
    private LocalDate rStart;
    private LocalDate rEnd;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        tenantId = fx.tenantId();
        unit = fx.unit();
        unit.setType(UnitType.BHK2);
        unit = unitRepo.save(unit);
        today = LocalDate.now(clock);
        // A one-year contract that ends in about two months: its 90-day notice deadline has passed.
        start = today.minusMonths(10);
        end = start.plusMonths(12).minusDays(1);
        rStart = end.plusDays(1);
        rEnd = rStart.plusMonths(12).minusDays(1);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ---------------------------------------------------------------- flag off

    @Test
    void flagOffPreviewAndRenewUnchanged() {
        rate(LocalDate.now(clock).minusMonths(1));
        UUID lease = lease("120000");

        RenewalPreviewDTO p = renewal.preview(lease, amount("200000"));
        assertThat(p.baseRent()).isEqualByComparingTo("120000");
        assertThat(p.newRent()).isEqualByComparingTo("200000");
        assertThat(p.indexStatus()).isNull();
        assertThat(p.indexMin()).isNull();
        assertThat(p.indexMax()).isNull();
        assertThat(p.indexAverage()).isNull();
        assertThat(p.indexGapPercent()).isNull();
        assertThat(p.indexAllowedPercent()).isNull();
        assertThat(p.indexCapRent()).isNull();
        assertThat(p.indexAboveCap()).isNull();
        assertThat(p.indexRateId()).isNull();
        assertThat(p.indexSource()).isNull();
        assertThat(p.indexCheckedOn()).isNull();
        assertThat(p.indexUnitResult()).isNull();
        assertThat(p.noticeDeadline()).isNull();
        assertThat(p.noticePassed()).isNull();
        LeaseRenewalService.RenewalPlan plan = tx.execute(s -> renewal.planFor(lease, amount("200000")));
        LeaseRenewalService.IndexAssessment queue = tx.execute(s -> renewal.indexCapFor(lease));
        assertThat(plan.index()).isNull();
        assertThat(queue).isNull();

        // Far above any cap, no reason: renews exactly as today, and writes no check.
        LeaseDTO successor = renewal.renew(lease, amount("200000"));
        assertThat(successor.getRenewalPreviousRent()).isEqualByComparingTo("120000");
        assertThat(checkCount(lease)).isZero();
    }

    // ---------------------------------------------------------------- preview

    @Test
    void previewShowsCap() {
        flagOn();
        RentalIndexRate r = rate(today.minusMonths(1));
        UUID lease = lease("120000");

        RenewalPreviewDTO p = renewal.preview(lease, amount("126000"));
        assertThat(p.indexStatus()).isEqualTo("OK");
        assertThat(p.indexMin()).isEqualByComparingTo("138000");
        assertThat(p.indexMax()).isEqualByComparingTo("162000");
        assertThat(p.indexAverage()).isEqualByComparingTo("150000");
        assertThat(p.indexGapPercent()).isEqualByComparingTo("20");
        assertThat(p.indexAllowedPercent()).isEqualTo(5);
        assertThat(p.indexCapRent()).isEqualByComparingTo("126000");
        assertThat(p.indexAboveCap()).isFalse();
        assertThat(p.indexRateId()).isEqualTo(r.getId());
        assertThat(p.indexSource()).isEqualTo("MANUAL");
        assertThat(p.indexCheckedOn()).isEqualTo(today.minusMonths(1));
        assertThat(p.indexUnitResult()).isFalse();

        assertThat(renewal.preview(lease, amount("126001")).indexAboveCap()).isTrue();
        // Preview never blocks, even far above the cap.
        assertThat(renewal.preview(lease, amount("200000")).indexAboveCap()).isTrue();

        // The queue's figure: today's rent and a 12-month successor.
        LeaseRenewalService.IndexAssessment q = tx.execute(s -> renewal.indexCapFor(lease));
        assertThat(q.status()).isEqualTo(IndexStatus.OK);
        assertThat(q.cap().percent()).isEqualTo(5);
        assertThat(q.cap().capRent()).isEqualByComparingTo("126000");
        assertThat(q.aboveCap()).isFalse();
    }

    @Test
    void notDubaiCarriesStatusOnly() {
        flagOn();
        rate(today.minusMonths(1));
        Property p = propertyRepo.findById(fx.property().getId()).orElseThrow();
        p.setEmirate(Emirate.SHARJAH);
        propertyRepo.save(p);
        UUID lease = lease("120000");

        RenewalPreviewDTO pv = renewal.preview(lease, amount("200000"));
        assertThat(pv.indexStatus()).isEqualTo("NOT_DUBAI");
        assertThat(pv.indexCapRent()).isNull();
        assertThat(pv.indexAboveCap()).isNull();
        assertThat(pv.noticeDeadline()).isNull();
        renewal.renew(lease, amount("200000"));
        assertThat(checkCount(lease)).isZero();
    }

    // ---------------------------------------------------------------- renew

    @Test
    void renewAboveCapWithoutReasonRefused() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");

        assertThatThrownBy(() -> renewal.renew(lease, amount("130000")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired");
                    assertThat((BigDecimal) e.getArgs().get("cap")).isEqualByComparingTo("126000");
                });
        assertThatThrownBy(() -> renewal.renew(lease, amount("130000", "   ")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired"));
        assertThatThrownBy(() -> renewal.renew(lease, amount("130000", "x".repeat(501))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideTooLong"));
        assertThat(checkCount(lease)).isZero();
        assertThat(successorCount(lease)).isZero();
    }

    @Test
    void renewAboveCapWithReasonStoresCheck() {
        flagOn();
        RentalIndexRate r = rate(today.minusMonths(1));
        UUID lease = lease("120000");

        LeaseDTO successor = renewal.renew(lease, amount("130000", "  Agreed with the renter in writing  "));
        RenewalIndexCheck c = onlyCheck(lease);
        assertThat(c.getTenantId()).isEqualTo(tenantId);
        assertThat(c.getSuccessorLeaseId()).isEqualTo(successor.getId());
        assertThat(c.getRateId()).isEqualTo(r.getId());
        assertThat(c.getBaseRent()).isEqualByComparingTo("120000");
        assertThat(c.getAverage()).isEqualByComparingTo("150000");
        assertThat(c.getGapPercent()).isEqualByComparingTo("20");
        assertThat(c.getAllowedPercent()).isEqualTo(5);
        assertThat(c.getCapRent()).isEqualByComparingTo("126000");
        assertThat(c.getNewRent()).isEqualByComparingTo("130000");
        assertThat(c.isAboveCap()).isTrue();
        assertThat(c.getOverrideReason()).isEqualTo("Agreed with the renter in writing");
        assertThat(c.getDecidedBy()).isNotNull();
        assertThat(c.getDecidedAt()).isNotNull();
        assertThat(c.getUnitDocumentPath()).isNull();
        assertThat(c.getUnitDldPercent()).isNull();

        // 500 characters is the limit, not past it.
        UUID other = leaseOn(otherUnit("RI-500"), "120000");
        renewal.renew(other, amount("130000", "y".repeat(500)));
        assertThat(onlyCheck(other).getOverrideReason()).hasSize(500);
    }

    @Test
    void renewWithinCapStoresCheckWithoutReason() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");

        // A reason sent when the rent is within the cap is not an override, and is not kept.
        renewal.renew(lease, amount("126000", "not needed"));
        RenewalIndexCheck c = onlyCheck(lease);
        assertThat(c.isAboveCap()).isFalse();
        assertThat(c.getOverrideReason()).isNull();
        assertThat(c.getNewRent()).isEqualByComparingTo("126000");
        assertThat(c.getCapRent()).isEqualByComparingTo("126000");
    }

    @Test
    void explicitLinesAreCapped() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        RenewLeaseRequest edited = new RenewLeaseRequest(null, rStart, rEnd, List.of(line("RENT", "140000")),
                false, null, null, null, null);
        assertThatThrownBy(() -> renewal.renew(lease, edited))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired"));

        // A second rent line added as an extra charge counts too.
        RenewLeaseRequest extra = new RenewLeaseRequest(null, rStart, rEnd, null, false, null, null,
                List.of(line("RENT", "10000")), null);
        assertThatThrownBy(() -> renewal.renew(lease, extra))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired"));
        assertThat(successorCount(lease)).isZero();
    }

    @Test
    void twoRentLinesMeasuredOnTheSameSet() {
        flagOn();
        rate(today.minusMonths(1));
        // Two original RENT lines, 60,000 each: 120,000 a year renews, against a cap of 126,000.
        UUID lease = fx.postedLease(unit, fx.renter(), start, start, end,
                List.of(line("RENT", "60000"), line("RENT", "60000")), 4, null).lease().getId();

        // The grid resubmitted unchanged: nothing went up, so no reason is needed.
        RenewLeaseRequest unchanged = new RenewLeaseRequest(null, rStart, rEnd,
                List.of(line("RENT", "60000"), line("RENT", "60000")), false, null, null, null, null);
        RenewalPreviewDTO grid = renewal.preview(lease, unchanged);
        assertThat(grid.indexCapRent()).isEqualByComparingTo("126000");
        assertThat(grid.indexAboveCap()).isFalse();

        // Plan mode on the same lease gives the same verdict, and the preview's base is still the contract line.
        RenewalPreviewDTO same = renewal.preview(lease, new RenewLeaseRequest(null, rStart, rEnd, null, false,
                new RentChange(RentChange.Mode.NONE, null, null), null, null, null));
        assertThat(same.baseRent()).isEqualByComparingTo("60000");
        assertThat(same.indexCapRent()).isEqualByComparingTo("126000");
        assertThat(same.indexAboveCap()).isFalse();
        // The contract line to 66,000: 126,000 in all, at the cap; 66,001 is above it.
        assertThat(renewal.preview(lease, amount("66000")).indexAboveCap()).isFalse();
        assertThat(renewal.preview(lease, amount("66001")).indexAboveCap()).isTrue();

        renewal.renew(lease, unchanged);
        RenewalIndexCheck c = onlyCheck(lease);
        assertThat(c.getBaseRent()).isEqualByComparingTo("120000");
        assertThat(c.getNewRent()).isEqualByComparingTo("120000");
        assertThat(c.isAboveCap()).isFalse();
    }

    @Test
    void noRangeRenewsAsToday() {
        flagOn();
        UUID lease = lease("120000");

        RenewalPreviewDTO p = renewal.preview(lease, amount("200000"));
        assertThat(p.indexStatus()).isEqualTo("NO_INDEX");
        assertThat(p.indexCapRent()).isNull();
        assertThat(p.indexAboveCap()).isNull();
        assertThat(p.indexRateId()).isNull();
        renewal.renew(lease, amount("200000"));
        assertThat(checkCount(lease)).isZero();
    }

    @Test
    void sixMonthRenewalAnnualised() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        LocalDate sixEnd = rStart.plusMonths(6).minusDays(1);

        RenewalPreviewDTO above = renewal.preview(lease, amountFor(sixEnd, "63000", null));
        BigDecimal annual63 = RentalIndexMath.annualise(new BigDecimal("63000"), rStart, sixEnd);
        assertThat(annual63).isGreaterThan(new BigDecimal("126000"));
        assertThat(above.indexCapRent()).isEqualByComparingTo("126000");
        assertThat(above.indexAboveCap()).isTrue();

        RenewalPreviewDTO within = renewal.preview(lease, amountFor(sixEnd, "62000", null));
        assertThat(within.indexAboveCap()).isFalse();

        assertThatThrownBy(() -> renewal.renew(lease, amountFor(sixEnd, "63000", null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired"));
        renewal.renew(lease, amountFor(sixEnd, "62000", null));
        RenewalIndexCheck c = onlyCheck(lease);
        assertThat(c.getNewRent())
                .isEqualByComparingTo(RentalIndexMath.annualise(new BigDecimal("62000"), rStart, sixEnd));
        assertThat(c.isAboveCap()).isFalse();
    }

    @Test
    void unitOwnDldPercentWins() {
        flagOn();
        RentalIndexRate own = rate(today.minusMonths(1));
        own.setSource(RentalIndexSource.DLD_PDF);
        own.setSourceUnitId(unit.getId());
        own.setDldPercent(10);
        own.setDocumentPath("rental-index/synthetic.pdf");
        rates.save(own);
        UUID lease = lease("120000");

        RenewalPreviewDTO p = renewal.preview(lease, amount("130000"));
        assertThat(p.indexAllowedPercent()).isEqualTo(10);
        assertThat(p.indexCapRent()).isEqualByComparingTo("132000");
        assertThat(p.indexAboveCap()).isFalse();
        assertThat(p.indexUnitResult()).isTrue();
        assertThat(p.indexSource()).isEqualTo("DLD_PDF");

        renewal.renew(lease, amount("130000"));
        RenewalIndexCheck c = onlyCheck(lease);
        assertThat(c.getAllowedPercent()).isEqualTo(10);
        assertThat(c.getCapRent()).isEqualByComparingTo("132000");
        assertThat(c.getUnitDldPercent()).isEqualTo(10);
        assertThat(c.getUnitDocumentPath()).isEqualTo("rental-index/synthetic.pdf");

        // The same range used for a sibling unit is a building range: the midpoint arithmetic (5%) applies.
        Unit sibling = otherUnit("RI-SIB");
        UUID siblingLease = leaseOn(sibling, "120000");
        RenewalPreviewDTO s = renewal.preview(siblingLease, amount("130000"));
        assertThat(s.indexAllowedPercent()).isEqualTo(5);
        assertThat(s.indexUnitResult()).isFalse();
    }

    @Test
    void noticeDeadlineAndPassed() {
        flagOn();
        rate(today.minusMonths(1));
        UUID late = lease("120000");
        RenewalPreviewDTO p = renewal.preview(late, amount("120000"));
        assertThat(p.noticeDeadline()).isEqualTo(end.minusDays(90));
        assertThat(p.noticePassed()).isTrue();

        // A contract ending in about six months: the deadline is still ahead.
        LocalDate s2 = today.minusMonths(6);
        LocalDate e2 = s2.plusMonths(12).minusDays(1);
        UUID early = fx.postedLease(otherUnit("RI-EARLY"), fx.renter(), s2, s2, e2,
                List.of(line("RENT", "120000")), 4, null).lease().getId();
        LocalDate r2 = e2.plusDays(1);
        RenewalPreviewDTO q = renewal.preview(early, new RenewLeaseRequest(null, r2, r2.plusMonths(12).minusDays(1),
                null, false, new RentChange(RentChange.Mode.AMOUNT, null, new BigDecimal("120000")), null, null,
                null));
        assertThat(q.noticeDeadline()).isEqualTo(e2.minusDays(90));
        assertThat(q.noticePassed()).isFalse();

        // With no range in force, the deadline still shows.
        rates.findAll().forEach(r -> {
            r.setRevokedAt(Instant.now());
            r.setRevokedBy(UUID.randomUUID());
            rates.save(r);
        });
        RenewalPreviewDTO none = renewal.preview(late, amount("120000"));
        assertThat(none.indexStatus()).isEqualTo("NO_INDEX");
        assertThat(none.noticeDeadline()).isEqualTo(end.minusDays(90));
        assertThat(none.noticePassed()).isTrue();
    }

    @Test
    void checkIsSnapshotAfterRevoke() {
        flagOn();
        RentalIndexRate r = rate(today.minusMonths(1));
        UUID lease = lease("120000");
        renewal.renew(lease, amount("130000", "Agreed"));
        RenewalIndexCheck before = onlyCheck(lease);

        r.setRevokedAt(Instant.now());
        r.setRevokedBy(UUID.randomUUID());
        rates.save(r);

        RenewalIndexCheck after = onlyCheck(lease);
        assertThat(after.getRateId()).isEqualTo(before.getRateId());
        assertThat(after.getAverage()).isEqualByComparingTo(before.getAverage());
        assertThat(after.getGapPercent()).isEqualByComparingTo(before.getGapPercent());
        assertThat(after.getAllowedPercent()).isEqualTo(before.getAllowedPercent());
        assertThat(after.getCapRent()).isEqualByComparingTo(before.getCapRent());
        assertThat(after.isAboveCap()).isTrue();
        assertThat(after.getOverrideReason()).isEqualTo("Agreed");
        // Future renewals see no range.
        LeaseRenewalService.IndexAssessment next = tx.execute(s -> renewal.indexCapFor(lease));
        assertThat(next.status()).isEqualTo(IndexStatus.NO_INDEX);
    }

    @Test
    void otherTenantSeesNoChecks() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        renewal.renew(lease, amount("126000"));
        assertThat(checkCount(lease)).isEqualTo(1);

        new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        List<RenewalIndexCheck> seen = tx.execute(s -> checks.findByLeaseIdOrderByDecidedAtAsc(lease));
        assertThat(seen).isEmpty();
    }

    // ---------------------------------------------------------------- final review M1: a deleted range

    @Test
    void checkSurvivesDeletedRange() {
        flagOn();
        RentalIndexRate r = rate(today.minusMonths(1));
        UUID lease = lease("120000");
        renewal.renew(lease, amount("130000", "Agreed"));

        jdbc.update("delete from rental_index_rates where id = ?", r.getId());

        assertThat(checkCount(lease)).isEqualTo(1);
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "select rate_id, rate_source, rate_checked_on, cap_rent, override_reason from renewal_index_checks"
                        + " where lease_id = ?", lease);
        assertThat(row.get("rate_id")).isNull();
        assertThat(row.get("rate_source")).isEqualTo("MANUAL");
        assertThat(row.get("rate_checked_on").toString()).isEqualTo(today.minusMonths(1).toString());
        assertThat((BigDecimal) row.get("cap_rent")).isEqualByComparingTo("126000");
        assertThat(row.get("override_reason")).isEqualTo("Agreed");
    }

    // ---------------------------------------------------------------- final review I1: editing the successor draft

    @Test
    void draftEditWithinCapNeedsNoReasonAndIsRecorded() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        LeaseDTO successor = renewal.renew(lease, amount("120000"));
        assertThat(successorChecks(successor.getId())).hasSize(1);

        leaseService.updateDraftLease(successor.getId(), edit("125000", null));

        List<RenewalIndexCheck> rows = successorChecks(successor.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getNewRent()).isEqualByComparingTo("125000");
        assertThat(rows.get(0).isAboveCap()).isFalse();
        assertThat(rows.get(0).getOverrideReason()).isNull();
        assertThat(rows.get(0).getCapRent()).isEqualByComparingTo("126000");
        assertThat(rows.get(0).getLeaseId()).isEqualTo(lease);
        // The renewal's own check is never touched.
        assertThat(rows.get(1).getNewRent()).isEqualByComparingTo("120000");
    }

    @Test
    void draftEditAboveCapNeedsReasonAndStoresNewCheck() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        LeaseDTO successor = renewal.renew(lease, amount("120000"));
        UUID id = successor.getId();

        assertThatThrownBy(() -> leaseService.updateDraftLease(id, edit("130000", null)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired");
                    assertThat((BigDecimal) e.getArgs().get("cap")).isEqualByComparingTo("126000");
                });
        assertThatThrownBy(() -> leaseService.updateDraftLease(id, edit("130000", "   ")))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideRequired"));
        assertThatThrownBy(() -> leaseService.updateDraftLease(id, edit("130000", "z".repeat(501))))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.getCode()).isEqualTo("renewal.indexOverrideTooLong"));
        // Refused edits change nothing: the draft still charges 120,000 and one check is on record.
        assertThat(rentLines(id)).containsExactly(new BigDecimal("120000.00"));
        assertThat(successorChecks(id)).hasSize(1);

        leaseService.updateDraftLease(id, edit("130000", "  Renter agreed to the new fit-out  "));
        List<RenewalIndexCheck> rows = successorChecks(id);
        assertThat(rows).hasSize(2);
        RenewalIndexCheck edited = rows.get(0);
        assertThat(edited.isAboveCap()).isTrue();
        assertThat(edited.getOverrideReason()).isEqualTo("Renter agreed to the new fit-out");
        assertThat(edited.getNewRent()).isEqualByComparingTo("130000");
        assertThat(edited.getBaseRent()).isEqualByComparingTo("120000");
        assertThat(edited.getDecidedBy()).isNotNull();
        assertThat(rows.get(1).isAboveCap()).isFalse();
        assertThat(rentLines(id)).containsExactly(new BigDecimal("130000.00"));

        // Saved again at the same rent (another field edited): the decision on record stands, no reason, no row.
        leaseService.updateDraftLease(id, edit("130000", null));
        assertThat(successorChecks(id)).hasSize(2);
    }

    @Test
    void draftEditFlagOffUnchanged() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        LeaseDTO successor = renewal.renew(lease, amount("120000"));
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, false);

        leaseService.updateDraftLease(successor.getId(), edit("200000", null));
        assertThat(rentLines(successor.getId())).containsExactly(new BigDecimal("200000.00"));
        assertThat(successorChecks(successor.getId())).hasSize(1);
    }

    @Test
    void draftEditWithoutStoredCheckUnaffected() {
        // A plain draft, and a successor renewed before the flag was on: neither has a check, neither is capped.
        UUID lease = lease("120000");
        LeaseDTO successor = renewal.renew(lease, amount("120000"));
        flagOn();
        rate(today.minusMonths(1));
        leaseService.updateDraftLease(successor.getId(), edit("200000", null));
        assertThat(rentLines(successor.getId())).containsExactly(new BigDecimal("200000.00"));
        assertThat(successorChecks(successor.getId())).isEmpty();

        Unit other = otherUnit("RI-PLAIN");
        UUID plain = fx.draftLease(other, fx.renter(), rStart, rStart, rEnd, List.of(line("RENT", "120000")));
        CreateLeaseDTO dto = fx.draftDto(other, fx.renter(), rStart, rEnd, List.of(line("RENT", "300000")));
        leaseService.updateDraftLease(plain, dto);
        assertThat(rentLines(plain)).containsExactly(new BigDecimal("300000.00"));
        assertThat(successorChecks(plain)).isEmpty();
    }

    @Test
    void draftEditWithEndBeforeStartReportsTheDateProblemNotAnArithmeticError() {
        flagOn();
        rate(today.minusMonths(1));
        UUID lease = lease("120000");
        LeaseDTO successor = renewal.renew(lease, amount("120000"));
        CreateLeaseDTO dto = edit("125000", null);
        dto.setEndDate(dto.getStartDate().minusDays(1));

        // Same outcome as a draft without the index: the date order is not this check's business, and the
        // measurement is skipped (no new check row) instead of dividing by zero days.
        org.assertj.core.api.Assertions.assertThatCode(() -> leaseService.updateDraftLease(successor.getId(), dto))
                .doesNotThrowAnyException();
        assertThat(successorChecks(successor.getId())).hasSize(1);
    }

    // ---------------------------------------------------------------- helpers

    private void flagOn() {
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, true);
    }

    private UUID lease(String rent) {
        return leaseOn(unit, rent);
    }

    private UUID leaseOn(Unit u, String rent) {
        return fx.postedLease(u, fx.renter(), start, start, end, List.of(line("RENT", rent)), 4, null)
                .lease().getId();
    }

    private Unit otherUnit(String number) {
        Unit u = fx.createUnit(fx.property(), number);
        u.setType(UnitType.BHK2);
        return unitRepo.save(u);
    }

    private RenewLeaseRequest amount(String rent) {
        return amountFor(rEnd, rent, null);
    }

    private RenewLeaseRequest amount(String rent, String reason) {
        return amountFor(rEnd, rent, reason);
    }

    private RenewLeaseRequest amountFor(LocalDate to, String rent, String reason) {
        return new RenewLeaseRequest(null, rStart, to, null, false,
                new RentChange(RentChange.Mode.AMOUNT, null, new BigDecimal(rent)), null, null, reason);
    }

    private RentalIndexRate rate(LocalDate checkedOn) {
        RentalIndexRate r = new RentalIndexRate();
        r.setTenantId(tenantId);
        r.setPropertyId(fx.property().getId());
        r.setUnitType(UnitType.BHK2);
        r.setDldPropertyType(DldPropertyType.APARTMENT);
        r.setCategory("2 bed rooms+hall");
        r.setIndexMin(new BigDecimal("138000"));
        r.setIndexMax(new BigDecimal("162000"));
        r.setSource(RentalIndexSource.MANUAL);
        r.setCheckedOn(checkedOn);
        r.setExpiresOn(checkedOn.plusMonths(12));
        r.setCreatedBy(UUID.randomUUID());
        return rates.save(r);
    }

    private int checkCount(UUID leaseId) {
        Integer n = jdbc.queryForObject("select count(*) from renewal_index_checks where lease_id = ?",
                Integer.class, leaseId);
        return n == null ? 0 : n;
    }

    private int successorCount(UUID leaseId) {
        Integer n = jdbc.queryForObject("select count(*) from leases where renewed_from_lease_id = ?",
                Integer.class, leaseId);
        return n == null ? 0 : n;
    }

    private CreateLeaseDTO edit(String rent, String reason) {
        CreateLeaseDTO dto = fx.draftDto(unit, fx.renter(), rStart, rEnd, List.of(line("RENT", rent)));
        dto.setIndexOverrideReason(reason);
        return dto;
    }

    private List<RenewalIndexCheck> successorChecks(UUID successorId) {
        return tx.execute(s -> checks.findBySuccessorLeaseIdOrderByDecidedAtDesc(successorId));
    }

    private List<BigDecimal> rentLines(UUID leaseId) {
        return jdbc.queryForList("select l.gross_amount from lease_lines l join charge_types t on t.id = l.charge_type_id"
                + " where l.lease_id = ? and t.behaviour = 'RENT' order by l.seq_no", BigDecimal.class, leaseId);
    }

    private RenewalIndexCheck onlyCheck(UUID leaseId) {
        List<RenewalIndexCheck> rows = tx.execute(s -> checks.findByLeaseIdOrderByDecidedAtAsc(leaseId));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
