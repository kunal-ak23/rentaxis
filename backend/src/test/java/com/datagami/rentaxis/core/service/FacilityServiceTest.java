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
import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        // Spot creation (single + bulk) now goes through saveAndFlush so the
        // constraint-race translation can catch the DataIntegrityViolationException
        // at the point of insert; updateParkingSpot still uses plain save().
        when(spotRepository.saveAndFlush(any(ParkingSpot.class))).thenAnswer(inv -> {
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
    void createAmenity_bookableFalse_staysFalse() {
        PropertyAmenity created = service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", null, null, false, null));

        assertThat(created.isBookable()).isFalse();
    }

    @Test
    void createAmenity_scopeSave_capturesTenantAmenityAndBuildingIds() {
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        PropertyAmenity created = service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", null, null, null, List.of(buildingId)));

        ArgumentCaptor<AmenityBuildingScope> captor = ArgumentCaptor.forClass(AmenityBuildingScope.class);
        verify(amenityScopeRepository).save(captor.capture());
        AmenityBuildingScope saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getAmenityId()).isEqualTo(created.getId());
        assertThat(saved.getBuildingId()).isEqualTo(buildingId);
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
    void updateAmenity_blankNameEn_throws400() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest("   ", null, null, null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("nameEn is required");

        // Same guard as createAmenity: reject before persisting the blank name.
        assertThat(existing.getNameEn()).isEqualTo("Gym");
        verify(amenityRepository, never()).save(any());
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
    void updateAmenity_scopeReplacement_deletesFlushesThenSavesInOrder() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest(null, null, null, null, null, List.of(buildingId)));

        InOrder order = inOrder(amenityScopeRepository);
        order.verify(amenityScopeRepository).deleteByTenantIdAndAmenityId(tenantId, existing.getId());
        order.verify(amenityScopeRepository).flush();
        order.verify(amenityScopeRepository).save(any(AmenityBuildingScope.class));
    }

    @Test
    void updateAmenity_scopeSave_capturesTenantAmenityAndBuildingIds() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest(null, null, null, null, null, List.of(buildingId)));

        ArgumentCaptor<AmenityBuildingScope> captor = ArgumentCaptor.forClass(AmenityBuildingScope.class);
        verify(amenityScopeRepository).save(captor.capture());
        AmenityBuildingScope saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getAmenityId()).isEqualTo(existing.getId());
        assertThat(saved.getBuildingId()).isEqualTo(buildingId);
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
    void getParkingSpot_wrongTenant_throwsNotFound() {
        ParkingSpot foreign = spot("B1-01");
        foreign.setTenantId(UUID.randomUUID());
        when(spotRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getParkingSpot(tenantId, foreign.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deactivateParkingSpot_setsActiveFalse() {
        ParkingSpot existing = spot("B1-01");
        when(spotRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.deactivateParkingSpot(tenantId, existing.getId());

        assertThat(existing.isActive()).isFalse();
    }

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
    void createParkingSpot_scopeSave_capturesTenantSpotAndBuildingIds() {
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        ParkingSpot created = service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-09", "B1", null, List.of(buildingId)));

        ArgumentCaptor<ParkingSpotBuildingScope> captor = ArgumentCaptor.forClass(ParkingSpotBuildingScope.class);
        verify(spotScopeRepository).save(captor.capture());
        ParkingSpotBuildingScope saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getParkingSpotId()).isEqualTo(created.getId());
        assertThat(saved.getBuildingId()).isEqualTo(buildingId);
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
        verify(spotRepository, times(3)).saveAndFlush(any(ParkingSpot.class));
    }

    @Test
    void bulkCreateParkingSpots_existingNumber_throws400() {
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, "B1-02"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);

        // The duplicate is caught by the pre-check loop before any spot in the
        // batch is created — nothing should have been persisted.
        verify(spotRepository, never()).save(any());
        verify(spotRepository, never()).saveAndFlush(any());
    }

    @Test
    void bulkCreateParkingSpots_emptyNumbers_throws400() {
        assertThatThrownBy(() -> service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of(), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void bulkCreateParkingSpots_tooManyNumbers_throws400() {
        List<String> numbers = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            numbers.add("B1-" + i);
        }

        assertThatThrownBy(() -> service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, numbers, null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createParkingSpot_uniqueConstraintRaceAtSave_throwsBusinessRuleViolation() {
        when(spotRepository.saveAndFlush(any(ParkingSpot.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_parking_spot_number\"")));

        assertThatThrownBy(() -> service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-10", "B1", null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void createParkingSpot_unrecognizedConstraintViolation_rethrowsOriginal() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("insert failed",
                new RuntimeException("duplicate key value violates unique constraint \"some_other_constraint\""));
        when(spotRepository.saveAndFlush(any(ParkingSpot.class))).thenThrow(original);

        assertThatThrownBy(() -> service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-11", "B1", null, null)))
                .isSameAs(original);
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

    @Test
    void updateParkingSpot_scopeSave_capturesTenantSpotAndBuildingIds() {
        ParkingSpot existing = spot("B1-01");
        when(spotRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        service.updateParkingSpot(tenantId, existing.getId(),
                new ParkingSpotUpdateRequest(null, null, null, null, List.of(buildingId)));

        ArgumentCaptor<ParkingSpotBuildingScope> captor = ArgumentCaptor.forClass(ParkingSpotBuildingScope.class);
        verify(spotScopeRepository).save(captor.capture());
        ParkingSpotBuildingScope saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getParkingSpotId()).isEqualTo(existing.getId());
        assertThat(saved.getBuildingId()).isEqualTo(buildingId);
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

    @Test
    void visibleFacilities_nonBookableAmenity_stillVisible() {
        // Visibility is driven by active + building scope, not bookable — a
        // display-only (non-bookable) amenity should still show up for renters.
        PropertyAmenity a = amenity(false);
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
    void amenityVisibleToUnit_nonBookable_isStillTrue() {
        PropertyAmenity a = amenity(false);

        assertThat(service.amenityVisibleToUnit(a, unitInProperty(buildingId))).isTrue();
    }

    @Test
    void visibleFacilities_parkingSpots_areVisiblePropertyWide() {
        ParkingSpot unscoped = spot("B1-01");
        ParkingSpot scopedToMyBuilding = spot("B1-02");
        ParkingSpot scopedElsewhere = spot("B1-03");
        UUID otherBuildingId = UUID.randomUUID();

        ParkingSpotBuildingScope myScope = new ParkingSpotBuildingScope();
        myScope.setParkingSpotId(scopedToMyBuilding.getId());
        myScope.setBuildingId(buildingId);

        ParkingSpotBuildingScope elsewhereScope = new ParkingSpotBuildingScope();
        elsewhereScope.setParkingSpotId(scopedElsewhere.getId());
        elsewhereScope.setBuildingId(otherBuildingId);

        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of());
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(tenantId, propertyId))
                .thenReturn(List.of(unscoped, scopedToMyBuilding, scopedElsewhere));
        FacilityService.VisibleFacilities visible =
                service.visibleFacilities(tenantId, unitInProperty(buildingId));

        assertThat(visible.parkingSpots())
                .containsExactlyInAnyOrder(unscoped, scopedToMyBuilding, scopedElsewhere);
    }

    @Test
    void parkingSpotVisibleToUnit_scopedToMyBuilding_isTrue() {
        ParkingSpot s = spot("B1-01");
        ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
        scope.setParkingSpotId(s.getId());
        scope.setBuildingId(buildingId);
        when(spotScopeRepository.findByParkingSpotId(s.getId())).thenReturn(List.of(scope));

        assertThat(service.parkingSpotVisibleToUnit(s, unitInProperty(buildingId))).isTrue();
    }

    @Test
    void parkingSpotVisibleToUnit_scopedElsewhere_isStillTrue() {
        ParkingSpot s = spot("B1-01");
        ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
        scope.setParkingSpotId(s.getId());
        scope.setBuildingId(UUID.randomUUID());
        assertThat(service.parkingSpotVisibleToUnit(s, unitInProperty(buildingId))).isTrue();
    }

    @Test
    void parkingSpotVisibleToUnit_unscoped_isTrue() {
        ParkingSpot s = spot("B1-01");
        when(spotScopeRepository.findByParkingSpotId(s.getId())).thenReturn(List.of());

        assertThat(service.parkingSpotVisibleToUnit(s, unitInProperty(buildingId))).isTrue();
    }

    @Test
    void parkingSpotVisibleToUnit_unitWithoutBuilding_canSeeAllPropertyParking() {
        ParkingSpot scoped = spot("B1-02");
        ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
        scope.setParkingSpotId(scoped.getId());
        scope.setBuildingId(buildingId);
        ParkingSpot unscoped = spot("B1-01");

        assertThat(service.parkingSpotVisibleToUnit(scoped, unitInProperty(null))).isTrue();
        assertThat(service.parkingSpotVisibleToUnit(unscoped, unitInProperty(null))).isTrue();
    }
}
