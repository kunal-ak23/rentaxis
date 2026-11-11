package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange;
import com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexImportResult;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeDTO;
import com.datagami.rentaxis.api.dto.rentalindex.RentalIndexRangeInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decree 43 in the renewal preview: the cap comes from the DLD range entered for
 * the unit's property, type and the successor's end year, and is absent otherwise.
 * Ahmed pays 80,000 (85,000 less a 5,000 discount) for 02/10/2026 → 01/10/2027 and
 * renews for the year ending 01/10/2028.
 */
@SpringBootTest
class RenewalReraCapIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired RentalIndexService rentalIndex;
    @Autowired UnitService unitService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate R_START = LocalDate.of(2027, 10, 2);
    private static final LocalDate R_END = LocalDate.of(2028, 10, 1);

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

    private UUID ahmed() {
        return fixtures.postedLease(CONTRACT, START, END,
                List.of(line("RENT", "85000", "5000")), 4, null).lease().getId();
    }

    private static RenewLeaseRequest request(RentChange change) {
        return new RenewLeaseRequest(LocalDate.of(2027, 9, 16), R_START, R_END, null, false, change, null, null);
    }

    private static RentChange percent(String p) {
        return new RentChange(RentChange.Mode.PERCENT, new BigDecimal(p), null);
    }

    private static RentalIndexRangeInput range(String type, int year, String min, String max) {
        return new RentalIndexRangeInput(null, type, year, new BigDecimal(min), new BigDecimal(max), "DLD 2026-11-11");
    }

    private UUID propertyId() {
        return fixtures.property().getId();
    }

    private UnitType unitType() {
        return fixtures.unit().getType();
    }

    @Test
    void previewCarriesTheCapWhenARangeMatchesTheNewEndYear() {
        // The measured 2027 two-bed range: 80,522–98,416 → average 89,469. Ahmed's 80,000 is
        // 10.584 % below → 5 % → at most 84,000. +8 % drafts 86,400, which is above it.
        rentalIndex.upsert(propertyId(), range(unitType().name(), R_END.getYear(), "80522", "98416"), UUID.randomUUID());
        unitService.setDewaPremiseNumber(fixtures.unit().getId(), "123456789");

        UUID lease = ahmed();
        RenewalPreviewDTO above = renewal.preview(lease, request(percent("8")));
        assertThat(above.indexYear()).isEqualTo(2028);
        assertThat(above.indexMin()).isEqualByComparingTo("80522");
        assertThat(above.indexMax()).isEqualByComparingTo("98416");
        assertThat(above.indexAverage()).isEqualByComparingTo("89469");
        assertThat(above.belowAveragePercent()).isEqualByComparingTo("10.584");
        assertThat(above.capPercent()).isEqualByComparingTo("5");
        assertThat(above.maxRent()).isEqualByComparingTo("84000.00");
        assertThat(above.exceedsCap()).isTrue();
        assertThat(above.indexEnteredAt()).isNotNull();
        assertThat(above.dewaPremiseNumber()).isEqualTo("123456789");
        // Advisory: the preview still drafts the 86,400 rent.
        assertThat(above.newRent()).isEqualByComparingTo("86400");

        // +4 % drafts 83,200: within the cap, same range.
        RenewalPreviewDTO within = renewal.preview(lease, request(percent("4")));
        assertThat(within.exceedsCap()).isFalse();
        assertThat(within.maxRent()).isEqualByComparingTo("84000.00");
    }

    @Test
    void previewHasNoCapWithoutAMatchingRange() {
        // A range for another year, and one for another unit type, are not this unit's.
        rentalIndex.upsert(propertyId(), range(unitType().name(), R_END.getYear() - 1, "80522", "98416"), UUID.randomUUID());
        UnitType other = unitType() == UnitType.BHK3 ? UnitType.BHK2 : UnitType.BHK3;
        rentalIndex.upsert(propertyId(), range(other.name(), R_END.getYear(), "80522", "98416"), UUID.randomUUID());

        RenewalPreviewDTO p = renewal.preview(ahmed(), request(percent("8")));
        assertThat(p.indexYear()).isNull();
        assertThat(p.indexMin()).isNull();
        assertThat(p.indexMax()).isNull();
        assertThat(p.indexAverage()).isNull();
        assertThat(p.belowAveragePercent()).isNull();
        assertThat(p.capPercent()).isNull();
        assertThat(p.maxRent()).isNull();
        assertThat(p.exceedsCap()).isNull();
        assertThat(p.indexEnteredAt()).isNull();
        assertThat(p.dewaPremiseNumber()).isNull();
        // The rest of the preview is untouched.
        assertThat(p.newRent()).isEqualByComparingTo("86400");
    }

    @Test
    void upsertReplacesTheRangeForTheSameTypeAndYear() {
        RentalIndexRangeDTO first = rentalIndex.upsert(propertyId(), range("2 BR", 2027, "80522", "98416"), UUID.randomUUID());
        RentalIndexRangeDTO second = rentalIndex.upsert(propertyId(), range("BHK2", 2027, "81000", "99000"), UUID.randomUUID());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.unitType()).isEqualTo(UnitType.BHK2);
        assertThat(second.minValue()).isEqualByComparingTo("81000");
        List<RentalIndexRangeDTO> listed = rentalIndex.list(propertyId());
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).sourceNote()).isEqualTo("DLD 2026-11-11");
    }

    @Test
    void importIsAllOrNothingAndNamesTheBadRows() {
        String building = fixtures.property().getNameEn();
        RentalIndexImportResult refused = rentalIndex.importRows(propertyId(), List.of(
                new RentalIndexRangeInput(building, "2 BR", 2026, new BigDecimal("83736"), new BigDecimal("102344"), null),
                new RentalIndexRangeInput("Some Other Tower", "1 BR", 2027, new BigDecimal("59054"), new BigDecimal("72176"), null),
                new RentalIndexRangeInput(building, "Villa", 2027, new BigDecimal("1"), new BigDecimal("2"), null),
                new RentalIndexRangeInput(building, "2 BR", 2027, new BigDecimal("98416"), new BigDecimal("80522"), null),
                new RentalIndexRangeInput(building, "2 BR", 2026, new BigDecimal("1"), new BigDecimal("2"), null)),
                UUID.randomUUID());
        assertThat(refused.saved()).isZero();
        assertThat(refused.errors()).containsExactly(
                "Row 3: Building \"Some Other Tower\" is not " + building,
                "Row 4: Unknown unit type \"Villa\"",
                "Row 5: Max must not be below min",
                "Row 6: BHK2 2026 appears twice");
        assertThat(rentalIndex.list(propertyId())).isEmpty();

        RentalIndexImportResult ok = rentalIndex.importRows(propertyId(), List.of(
                new RentalIndexRangeInput(building, "2 BR", 2026, new BigDecimal("83736"), new BigDecimal("102344"), null),
                new RentalIndexRangeInput("", "1 BR", 2027, new BigDecimal("59054"), new BigDecimal("72176"), "note")),
                UUID.randomUUID());
        assertThat(ok.saved()).isEqualTo(2);
        assertThat(ok.errors()).isEmpty();
        assertThat(rentalIndex.list(propertyId())).extracting(RentalIndexRangeDTO::unitType)
                .containsExactly(UnitType.BHK1, UnitType.BHK2);
    }

    @Test
    void dewaPremiseNumberIsNineDigitsOrBlank() {
        Unit u = fixtures.unit();
        assertThatThrownBy(() -> unitService.setDewaPremiseNumber(u.getId(), "12345678"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("9 digits");
        assertThatThrownBy(() -> unitService.setDewaPremiseNumber(u.getId(), "12345678a"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(unitService.setDewaPremiseNumber(u.getId(), " 123456789 ").getDewaPremiseNumber()).isEqualTo("123456789");
        assertThat(unitService.setDewaPremiseNumber(u.getId(), "  ").getDewaPremiseNumber()).isNull();
    }
}
