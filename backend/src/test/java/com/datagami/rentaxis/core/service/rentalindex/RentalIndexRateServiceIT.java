package com.datagami.rentaxis.core.service.rentalindex;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.rentalindex.RentalIndexRateService.IndexStatus;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.DldPropertyType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.RentalIndexSource;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Range lookup (spec §14.6) and the unit's index status. */
@SpringBootTest
class RentalIndexRateServiceIT extends AbstractPostgresIT {

    private static final LocalDate ON = LocalDate.of(2026, 11, 25);

    @Autowired RentalIndexRateService service;
    @Autowired RentalIndexRateRepository rates;
    @Autowired TenantFeatureService features;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired BuildingRepository buildingRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;

    private LeaseTestFixtures fx;
    private UUID tenantId;
    private Property property;
    private Building tower;
    private Unit unit;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        tenantId = fx.tenantId();
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, true);
        property = fx.property();
        Building b = new Building();
        b.setProperty(property);
        b.setNameEn("Tower A");
        b.setTenantId(tenantId);
        tower = buildingRepo.save(b);
        unit = unit(property, tower, UnitType.BHK2, "RI-1");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void lookupPrefersBuildingOverProperty() {
        RentalIndexRate onBuilding = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 3, 1));
        rate(null, UnitType.BHK2, LocalDate.of(2026, 10, 1));
        assertThat(service.inForce(unit, ON)).map(RentalIndexRate::getId).contains(onBuilding.getId());

        // A unit with no building, and one whose building has no range, fall back to the property-level range.
        Unit loose = unit(property, null, UnitType.BHK2, "RI-2");
        Building other = new Building();
        other.setProperty(property);
        other.setNameEn("Tower B");
        other.setTenantId(tenantId);
        Unit inOther = unit(property, buildingRepo.save(other), UnitType.BHK2, "RI-3");
        assertThat(service.inForce(loose, ON)).hasValueSatisfying(r -> assertThat(r.getBuildingId()).isNull());
        assertThat(service.inForce(inOther, ON)).hasValueSatisfying(r -> assertThat(r.getBuildingId()).isNull());
        assertThat(service.statusFor(unit, ON)).isEqualTo(IndexStatus.OK);
    }

    @Test
    void newestCheckedOnWins() {
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 1, 10));
        RentalIndexRate newest = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 6, 10));
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 4, 10));
        assertThat(service.inForce(unit, ON)).map(RentalIndexRate::getId).contains(newest.getId());

        // Same checked_on: the later created_at wins.
        RentalIndexRate sameDayLater = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 6, 10));
        sameDayLater.setCreatedAt(Instant.now().plusSeconds(60));
        rates.save(sameDayLater);
        assertThat(service.inForce(unit, ON)).map(RentalIndexRate::getId).contains(sameDayLater.getId());
    }

    @Test
    void revokedIgnored() {
        RentalIndexRate older = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 2, 1));
        RentalIndexRate newer = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 8, 1));
        newer.setRevokedAt(Instant.now());
        newer.setRevokedBy(UUID.randomUUID());
        rates.save(newer);
        assertThat(service.inForce(unit, ON)).map(RentalIndexRate::getId).contains(older.getId());
    }

    @Test
    void expiredIgnored() {
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2025, 11, 24)); // expires 2026-11-24, the day before ON
        assertThat(service.inForce(unit, ON)).isEmpty();
        assertThat(service.statusFor(unit, ON)).isEqualTo(IndexStatus.NO_INDEX);

        RentalIndexRate lastDay = rate(tower.getId(), UnitType.BHK2, LocalDate.of(2025, 11, 25)); // expires on ON
        assertThat(service.inForce(unit, ON)).map(RentalIndexRate::getId).contains(lastDay.getId());
    }

    @Test
    void otherUnitTypeIgnored() {
        rate(tower.getId(), UnitType.BHK1, LocalDate.of(2026, 6, 1));
        rate(null, UnitType.STUDIO, LocalDate.of(2026, 6, 1));
        assertThat(service.inForce(unit, ON)).isEmpty();
    }

    @Test
    void notDubaiStatus() {
        property.setEmirate(Emirate.SHARJAH);
        propertyRepo.save(property);
        unit = unitRepo.findById(unit.getId()).orElseThrow();
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 6, 1));
        assertThat(service.statusFor(unit, ON)).isEqualTo(IndexStatus.NOT_DUBAI);
        assertThat(service.applies(unit)).isFalse();
    }

    @Test
    void noEmirateStatus() {
        // properties.emirate is NOT NULL today, so a property without one exists only in memory.
        Property bare = new Property();
        bare.setId(property.getId());
        bare.setTenantId(tenantId);
        bare.setEmirate(null);
        Unit u = new Unit();
        u.setProperty(bare);
        u.setType(UnitType.BHK2);
        u.setTenantId(tenantId);
        assertThat(service.statusFor(u, ON)).isEqualTo(IndexStatus.NO_EMIRATE);
        assertThat(service.applies(u)).isFalse();
    }

    @Test
    void retailNotResidential() {
        Unit shop = unit(property, tower, UnitType.RETAIL, "RI-SHOP");
        Unit office = unit(property, tower, UnitType.OFFICE, "RI-OFF");
        rate(tower.getId(), UnitType.RETAIL, LocalDate.of(2026, 6, 1));
        assertThat(service.statusFor(shop, ON)).isEqualTo(IndexStatus.NOT_RESIDENTIAL);
        assertThat(service.statusFor(office, ON)).isEqualTo(IndexStatus.NOT_RESIDENTIAL);
        assertThat(service.applies(shop)).isFalse();
        assertThat(service.applies(unit)).isTrue();
    }

    @Test
    void flagOffAppliesFalse() {
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 6, 1));
        features.setEnabled(tenantId, TenantFeature.RENTAL_INDEX, false);
        assertThat(service.applies(unit)).isFalse();
        assertThat(service.statusFor(unit, ON)).isNull();
    }

    @Test
    void otherTenantsRangeNeverFound() {
        rate(tower.getId(), UnitType.BHK2, LocalDate.of(2026, 6, 1));
        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        features.setEnabled(other.tenantId(), TenantFeature.RENTAL_INDEX, true);
        // Context is now the other tenant: its own unit has no range, and ours is not visible.
        Unit theirs = other.unit();
        theirs.setType(UnitType.BHK2);
        unitRepo.save(theirs);
        assertThat(service.inForce(theirs, ON)).isEmpty();
        assertThat(service.inForce(unit, ON)).isEmpty();
    }

    private Unit unit(Property p, Building b, UnitType type, String number) {
        Unit u = fx.createUnit(p, number);
        u.setBuilding(b);
        u.setType(type);
        return unitRepo.save(u);
    }

    private RentalIndexRate rate(UUID buildingId, UnitType type, LocalDate checkedOn) {
        RentalIndexRate r = new RentalIndexRate();
        r.setTenantId(tenantId);
        r.setPropertyId(property.getId());
        r.setBuildingId(buildingId);
        r.setUnitType(type);
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
}
