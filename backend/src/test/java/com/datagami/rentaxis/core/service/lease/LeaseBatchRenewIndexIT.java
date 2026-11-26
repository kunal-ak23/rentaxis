package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.LeaseBatch;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.RenewalIndexCheck;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rental index in bulk renew (spec 2026-11-25 §8). Range 138,000–162,000 (average 150,000): a 120,000 rent has
 * gap 20% so 5% (cap 126,000); a 140,000 rent has gap 6.7% so 0% (cap 140,000). Synthetic figures only.
 */
@SpringBootTest
class LeaseBatchRenewIndexIT extends AbstractPostgresIT {

    @Autowired LeaseBatchService batch;
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
    private int unitSeq = 300;

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
        start = today.minusMonths(10);
        end = start.plusMonths(12).minusDays(1);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private static LeaseBatch.RentRule add(String amount) {
        return new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.ADD, null, new BigDecimal(amount));
    }

    private LeaseBatch.RenewRequest request(List<UUID> ids, String reason) {
        return new LeaseBatch.RenewRequest(ids, add("3000"), null, null, false, false, null, reason);
    }

    @Test
    void mixedRowsWithoutReason() {
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, true);
        rate();
        UUID within = leaseOn(unit, "120000");            // 123,000 against 126,000
        UUID above = leaseOn(otherUnit(UnitType.BHK2), "140000"); // 143,000 against 140,000
        UUID noIndex = leaseOn(otherUnit(UnitType.BHK3), "140000"); // no range for 3 bed

        List<LeaseBatch.RenewRow> rows = batch.renew(request(List.of(within, above, noIndex), null));

        assertThat(rows).extracting(LeaseBatch.RenewRow::ok).containsExactly(true, false, true);
        LeaseBatch.RenewRow w = rows.get(0);
        assertThat(w.indexStatus()).isEqualTo("OK");
        assertThat(w.indexAllowedPercent()).isEqualTo(5);
        assertThat(w.indexCapRent()).isEqualByComparingTo("126000");
        assertThat(w.indexAboveCap()).isFalse();
        LeaseBatch.RenewRow a = rows.get(1);
        assertThat(a.code()).isEqualTo("renewal.indexOverrideRequired");
        assertThat((BigDecimal) a.args().get("cap")).isEqualByComparingTo("140000");
        assertThat(a.renewalLeaseId()).isNull();
        LeaseBatch.RenewRow n = rows.get(2);
        assertThat(n.indexStatus()).isEqualTo("NO_INDEX");
        assertThat(n.indexAllowedPercent()).isNull();
        assertThat(n.indexCapRent()).isNull();
        assertThat(n.indexAboveCap()).isFalse();
        // The refused contract left nothing behind; the others carry on.
        assertThat(successorCount(above)).isZero();
        assertThat(successorCount(within)).isEqualTo(1);
        assertThat(successorCount(noIndex)).isEqualTo(1);
    }

    @Test
    void sharedReasonStoredOnEveryAboveCapRow() {
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, true);
        rate();
        UUID within = leaseOn(unit, "120000");
        UUID aboveOne = leaseOn(otherUnit(UnitType.BHK2), "140000");
        UUID aboveTwo = leaseOn(otherUnit(UnitType.BHK2), "150000");

        List<LeaseBatch.RenewRow> rows = batch.renew(request(List.of(within, aboveOne, aboveTwo),
                "  Agreed with the renters  "));

        assertThat(rows).extracting(LeaseBatch.RenewRow::ok).containsExactly(true, true, true);
        assertThat(rows.get(1).indexAboveCap()).isTrue();
        assertThat(rows.get(1).indexCapRent()).isEqualByComparingTo("140000");
        assertThat(rows.get(1).indexAllowedPercent()).isZero();
        assertThat(onlyCheck(aboveOne).getOverrideReason()).isEqualTo("Agreed with the renters");
        assertThat(onlyCheck(aboveTwo).getOverrideReason()).isEqualTo("Agreed with the renters");
        RenewalIndexCheck ok = onlyCheck(within);
        assertThat(ok.isAboveCap()).isFalse();
        assertThat(ok.getOverrideReason()).isNull();

        // Too long: refused per row.
        UUID long1 = leaseOn(otherUnit(UnitType.BHK2), "140000");
        LeaseBatch.RenewRow tooLong = batch.renew(request(List.of(long1), "z".repeat(501))).get(0);
        assertThat(tooLong.ok()).isFalse();
        assertThat(tooLong.code()).isEqualTo("renewal.indexOverrideTooLong");
    }

    @Test
    void flagOffBulkUnchanged() {
        rate();
        UUID above = leaseOn(unit, "140000");

        LeaseBatch.RenewRow row = batch.renew(request(List.of(above), null)).get(0);

        assertThat(row.ok()).isTrue();
        assertThat(row.newRent()).isEqualByComparingTo("143000");
        assertThat(row.indexStatus()).isNull();
        assertThat(row.indexAllowedPercent()).isNull();
        assertThat(row.indexCapRent()).isNull();
        assertThat(row.indexAboveCap()).isNull();
        assertThat(checkCount(above)).isZero();
    }

    @Test
    void flagOffRefusalsKeepTheSingleRenewsGateOrder() {
        // A contract that already has a successor, asked to renew by PERCENT over a different term length: the
        // single renew's own gate ("already renewed") answers, not the plan's term-length refusal.
        UUID lease = leaseOn(unit, "120000");
        fx.withLeaseServices(leaseService, chequeGeneration, posting);
        batch.renew(request(List.of(lease), null));
        assertThat(successorCount(lease)).isEqualTo(1);

        LeaseBatch.RenewRow row = batch.renew(new LeaseBatch.RenewRequest(List.of(lease),
                new LeaseBatch.RentRule(LeaseBatch.RentRule.Mode.PERCENT, new BigDecimal("3"), null), 6, null,
                false, false, null)).get(0);

        assertThat(row.ok()).isFalse();
        assertThat(row.message()).contains("has already been renewed");
        assertThat(row.indexStatus()).isNull();
    }

    // ------------------------------------------------------------------

    private UUID leaseOn(Unit u, String rent) {
        return fx.postedLease(u, fx.renter(), start, start, end, List.of(line("RENT", rent)), 4, null)
                .lease().getId();
    }

    private Unit otherUnit(UnitType type) {
        Unit u = fx.createUnit(fx.property(), "BI-" + (unitSeq++));
        u.setType(type);
        return unitRepo.save(u);
    }

    private RentalIndexRate rate() {
        RentalIndexRate r = new RentalIndexRate();
        r.setTenantId(tenantId);
        r.setPropertyId(fx.property().getId());
        r.setUnitType(UnitType.BHK2);
        r.setDldPropertyType(DldPropertyType.APARTMENT);
        r.setCategory("2 bed rooms+hall");
        r.setIndexMin(new BigDecimal("138000"));
        r.setIndexMax(new BigDecimal("162000"));
        r.setSource(RentalIndexSource.MANUAL);
        LocalDate checkedOn = today.minusMonths(1);
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

    private RenewalIndexCheck onlyCheck(UUID leaseId) {
        List<RenewalIndexCheck> rows = tx.execute(s -> checks.findByLeaseIdOrderByDecidedAtAsc(leaseId));
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }
}
