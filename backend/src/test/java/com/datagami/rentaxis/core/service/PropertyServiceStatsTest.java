package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PropertyStatsDTO;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for the production 500 on {@code GET /api/v1/properties}:
 * {@link PropertyService#calculateStats} summed {@code Unit.expectedRent}/
 * {@code actualRent} via {@code BigDecimal.ZERO.add(...)} with no null guard.
 * Hibernate overwrites the Java-side {@code BigDecimal.ZERO} default with a
 * literal {@code null} when the DB column is NULL (e.g. a unit inserted via
 * {@code POST /api/v1/units}, which binds the raw entity with no
 * validation) — so a single unit with a null rent value 500'd the entire
 * properties list for every property, not just the affected one.
 */
class PropertyServiceStatsTest {

    private PropertyRepository propertyRepository;
    private UnitRepository unitRepository;
    private UserService userService;
    private PropertyService service;

    @BeforeEach
    void setUp() {
        propertyRepository = mock(PropertyRepository.class);
        unitRepository = mock(UnitRepository.class);
        BuildingRepository buildingRepository = mock(BuildingRepository.class);
        UserPropertyAssignmentRepository propertyAssignmentRepository = mock(UserPropertyAssignmentRepository.class);
        userService = mock(UserService.class);

        service = new PropertyService(
                propertyRepository, unitRepository, buildingRepository, propertyAssignmentRepository, userService,
                mock(PropertyAccountService.class));

        // filterByRole reads SecurityContextHolder; no authentication set
        // means "no role-based filtering", matching the SUPER_ADMIN/no-filter
        // path exercised by this test.
        SecurityContextHolder.clearContext();
        when(userService.getAssignedManagers(any(UUID.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getAllPropertiesWithStats_unitWithNullRentFields_doesNotThrow() {
        Property property = newProperty();
        when(propertyRepository.findAll()).thenReturn(List.of(property));

        Unit nullRentUnit = new Unit();
        nullRentUnit.setStatus(UnitStatus.VACANT);
        nullRentUnit.setExpectedRent(null);
        nullRentUnit.setActualRent(null);
        when(unitRepository.findByPropertyId(property.getId())).thenReturn(List.of(nullRentUnit));

        assertThatCode(service::getAllPropertiesWithStats).doesNotThrowAnyException();
    }

    @Test
    void getAllPropertiesWithStats_unitWithNullRentFields_treatsNullAsZero() {
        Property property = newProperty();
        when(propertyRepository.findAll()).thenReturn(List.of(property));

        Unit nullRentUnit = new Unit();
        nullRentUnit.setStatus(UnitStatus.OCCUPIED);
        nullRentUnit.setExpectedRent(null);
        nullRentUnit.setActualRent(null);
        Unit normalUnit = new Unit();
        normalUnit.setStatus(UnitStatus.VACANT);
        normalUnit.setExpectedRent(new BigDecimal("5000"));
        normalUnit.setActualRent(new BigDecimal("4800"));
        when(unitRepository.findByPropertyId(property.getId())).thenReturn(List.of(nullRentUnit, normalUnit));

        List<PropertyStatsDTO> result = service.getAllPropertiesWithStats();

        assertThat(result).hasSize(1);
        PropertyStatsDTO dto = result.get(0);
        // Null unit contributes zero, not an exception or a skipped row.
        assertThat(dto.getRevenueAtCapacity()).isEqualByComparingTo("5000");
        assertThat(dto.getActualRevenue()).isEqualByComparingTo("4800");
        assertThat(dto.getPropertyCount()).isEqualTo(2);
        assertThat(dto.getVacancies()).isEqualTo(1);
    }

    @Test
    void getAllPropertiesWithStats_allUnitsHaveRentValues_sumsCorrectly() {
        Property property = newProperty();
        when(propertyRepository.findAll()).thenReturn(List.of(property));

        Unit unitA = new Unit();
        unitA.setStatus(UnitStatus.OCCUPIED);
        unitA.setExpectedRent(new BigDecimal("3000"));
        unitA.setActualRent(new BigDecimal("3000"));
        Unit unitB = new Unit();
        unitB.setStatus(UnitStatus.OCCUPIED);
        unitB.setExpectedRent(new BigDecimal("4500.50"));
        unitB.setActualRent(new BigDecimal("4500.50"));
        when(unitRepository.findByPropertyId(property.getId())).thenReturn(List.of(unitA, unitB));

        List<PropertyStatsDTO> result = service.getAllPropertiesWithStats();

        assertThat(result.get(0).getRevenueAtCapacity()).isEqualByComparingTo("7500.50");
        assertThat(result.get(0).getActualRevenue()).isEqualByComparingTo("7500.50");
    }

    private Property newProperty() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Test Property");
        return property;
    }
}
