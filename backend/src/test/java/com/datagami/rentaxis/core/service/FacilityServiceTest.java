package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.AmenityBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacilityServiceTest {

    private PropertyAmenityRepository amenityRepository;
    private AmenityBuildingScopeRepository amenityScopeRepository;
    private ParkingSpotRepository spotRepository;
    private ParkingSpotBuildingScopeRepository spotScopeRepository;
    private PropertyRepository propertyRepository;
    private BuildingRepository buildingRepository;
    private FacilityService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        amenityRepository = mock(PropertyAmenityRepository.class);
        amenityScopeRepository = mock(AmenityBuildingScopeRepository.class);
        spotRepository = mock(ParkingSpotRepository.class);
        spotScopeRepository = mock(ParkingSpotBuildingScopeRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingRepository = mock(BuildingRepository.class);
        service = new FacilityService(amenityRepository, amenityScopeRepository,
                spotRepository, spotScopeRepository, propertyRepository, buildingRepository);

        when(amenityRepository.save(any(PropertyAmenity.class))).thenAnswer(inv -> {
            PropertyAmenity a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
        when(spotRepository.save(any(ParkingSpot.class))).thenAnswer(inv -> {
            ParkingSpot s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(propertyRepository.existsByIdAndTenantId(propertyId, tenantId)).thenReturn(true);
    }

    private Building buildingInProperty(UUID id) {
        Building b = new Building();
        b.setId(id);
        return b;
    }

    private Unit unitInProperty(UUID unitBuildingId) {
        Property property = new Property();
        property.setId(propertyId);
        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);
        if (unitBuildingId != null) {
            Building building = buildingInProperty(unitBuildingId);
            unit.setBuilding(building);
        }
        return unit;
    }

    private PropertyAmenity amenity(boolean bookable) {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(bookable);
        return a;
    }

    private ParkingSpot spot(String spotNumber) {
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPropertyId(propertyId);
        s.setSpotNumber(spotNumber);
        s.setCovered(true);
        s.setActive(true);
        return s;
    }

    // ---- amenity CRUD ----

    @Test
    void createAmenity_savesWithTenantPropertyAndScopes() {
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        PropertyAmenity created = service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", "نادي", "24/7 gym", null, List.of(buildingId)));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.isBookable()).isTrue();
        assertThat(created.isActive()).isTrue();
        verify(amenityScopeRepository).save(any(AmenityBuildingScope.class));
    }

    @Test
    void createAmenity_propertyNotInTenant_throwsNotFound() {
        UUID foreignProperty = UUID.randomUUID();
        when(propertyRepository.existsByIdAndTenantId(foreignProperty, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service.createAmenity(tenantId, new AmenityCreateRequest(
                foreignProperty, "Gym", null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createAmenity_buildingFromOtherProperty_throws400() {
        when(buildingRepository.findByPropertyId(propertyId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", null, null, null, List.of(UUID.randomUUID()))))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createAmenity_nullBookable_defaultsToTrue() {
        PropertyAmenity created = service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", null, null, null, null));

        assertThat(created.isBookable()).isTrue();
    }

    @Test
    void updateAmenity_nullFieldsLeaveValuesUnchanged() {
        PropertyAmenity existing = amenity(true);
        existing.setDescription("old");
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        PropertyAmenity updated = service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest("Pool", null, null, null, null, null));

        assertThat(updated.getNameEn()).isEqualTo("Pool");
        assertThat(updated.getDescription()).isEqualTo("old");
        assertThat(updated.isBookable()).isTrue();
    }

    @Test
    void updateAmenity_nonNullBuildingIdsReplacesScopeSet() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest(null, null, null, null, null, List.of(buildingId)));

        verify(amenityScopeRepository).deleteByTenantIdAndAmenityId(tenantId, existing.getId());
        verify(amenityScopeRepository).save(any(AmenityBuildingScope.class));
    }

    @Test
    void getAmenity_wrongTenant_throwsNotFound() {
        PropertyAmenity foreign = amenity(true);
        foreign.setTenantId(UUID.randomUUID());
        when(amenityRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getAmenity(tenantId, foreign.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deactivateAmenity_setsActiveFalse() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.deactivateAmenity(tenantId, existing.getId());

        assertThat(existing.isActive()).isFalse();
    }

    // ---- parking CRUD ----

    @Test
    void createParkingSpot_duplicateNumber_throws400() {
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, "B1-07"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-07", "B1", null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createParkingSpot_nullCovered_defaultsToTrue() {
        ParkingSpot created = service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-08", "B1", null, null));

        assertThat(created.isCovered()).isTrue();
    }

    @Test
    void bulkCreateParkingSpots_createsOnePerNumberWithSharedAttributes() {
        List<ParkingSpot> created = service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02", "B1-03"),
                        "B1", false, null));

        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(s -> {
            assertThat(s.getLevel()).isEqualTo("B1");
            assertThat(s.isCovered()).isFalse();
            assertThat(s.getPropertyId()).isEqualTo(propertyId);
        });
        verify(spotRepository, times(3)).save(any(ParkingSpot.class));
    }

    @Test
    void bulkCreateParkingSpots_existingNumber_throws400() {
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, "B1-02"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void updateParkingSpot_changedNumberCollidesWithAnotherSpot_throws400() {
        ParkingSpot existing = spot("B1-01");
        when(spotRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(
                tenantId, propertyId, "B1-02", existing.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.updateParkingSpot(tenantId, existing.getId(),
                new ParkingSpotUpdateRequest("B1-02", null, null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void updateParkingSpot_changedNumberFree_updatesSuccessfully() {
        ParkingSpot existing = spot("B1-01");
        when(spotRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(
                tenantId, propertyId, "B1-02", existing.getId())).thenReturn(false);

        ParkingSpot updated = service.updateParkingSpot(tenantId, existing.getId(),
                new ParkingSpotUpdateRequest("B1-02", null, null, null, null));

        assertThat(updated.getSpotNumber()).isEqualTo("B1-02");
    }

    // ---- renter visibility ----

    @Test
    void visibleFacilities_unscopedAmenity_visibleToAnyUnit() {
        PropertyAmenity a = amenity(true);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of(a));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(a.getId()))).thenReturn(List.of());
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities visible =
                service.visibleFacilities(tenantId, unitInProperty(buildingId));

        assertThat(visible.amenities()).containsExactly(a);
    }

    @Test
    void visibleFacilities_scopedAmenity_hiddenFromOtherTower() {
        PropertyAmenity a = amenity(true);
        AmenityBuildingScope scope = new AmenityBuildingScope();
        scope.setAmenityId(a.getId());
        scope.setBuildingId(buildingId);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of(a));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(a.getId()))).thenReturn(List.of(scope));
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities otherTower =
                service.visibleFacilities(tenantId, unitInProperty(UUID.randomUUID()));
        FacilityService.VisibleFacilities scopedTower =
                service.visibleFacilities(tenantId, unitInProperty(buildingId));

        assertThat(otherTower.amenities()).isEmpty();
        assertThat(scopedTower.amenities()).containsExactly(a);
    }

    @Test
    void visibleFacilities_unitWithoutBuilding_seesOnlyUnscoped() {
        PropertyAmenity unscoped = amenity(true);
        PropertyAmenity scoped = amenity(true);
        AmenityBuildingScope scope = new AmenityBuildingScope();
        scope.setAmenityId(scoped.getId());
        scope.setBuildingId(buildingId);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of(unscoped, scoped));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(unscoped.getId(), scoped.getId())))
                .thenReturn(List.of(scope));
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities visible =
                service.visibleFacilities(tenantId, unitInProperty(null));

        assertThat(visible.amenities()).containsExactly(unscoped);
    }

    @Test
    void amenityVisibleToUnit_differentProperty_isFalse() {
        PropertyAmenity a = amenity(true);
        a.setPropertyId(UUID.randomUUID());

        assertThat(service.amenityVisibleToUnit(a, unitInProperty(buildingId))).isFalse();
    }
}
