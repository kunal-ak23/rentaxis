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
                propertyRepository, unitRepository, buildingRepository,
                new com.datagami.rentaxis.core.security.PropertyScope(new com.datagami.rentaxis.core.security.LeaseAccessPolicy(
                        propertyAssignmentRepository, mock(com.datagami.rentaxis.domain.repository.RenterRepository.class))),
                userService,
                mock(PropertyAccountService.class));

        // filterByRole reads SecurityContextHolder; no authentication set
        // means "no role-based filtering", matching the SUPER_ADMIN/no-filter
        // path exercised by this test.
        SecurityContextHolder.clearContext();
        when(userService.getAssignedManagersByProperty(any())).thenReturn(java.util.Map.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * The per-property figures now come from one aggregate ({@code UnitRepository.statsByProperty},
     * which coalesces null rents to zero in SQL — PropertyStatsIT covers that against Postgres);
     * this pins the mapping of its row.
     */
    @Test
    void getAllPropertiesWithStats_mapsTheAggregateRow() {
        Property property = newProperty();
        when(propertyRepository.findAll()).thenReturn(List.of(property));
        when(unitRepository.statsByProperty(any())).thenReturn(java.util.Collections.singletonList(
                new Object[]{property.getId(), 2L, 1L, new BigDecimal("7500.50"), new BigDecimal("4800")}));

        List<PropertyStatsDTO> result = service.getAllPropertiesWithStats();

        assertThat(result).hasSize(1);
        PropertyStatsDTO dto = result.get(0);
        assertThat(dto.getRevenueAtCapacity()).isEqualByComparingTo("7500.50");
        assertThat(dto.getActualRevenue()).isEqualByComparingTo("4800");
        assertThat(dto.getPropertyCount()).isEqualTo(2);
        assertThat(dto.getVacancies()).isEqualTo(1);
    }

    @Test
    void getAllPropertiesWithStats_aPropertyWithNoUnitsIsZeros() {
        Property property = newProperty();
        when(propertyRepository.findAll()).thenReturn(List.of(property));
        when(unitRepository.statsByProperty(any())).thenReturn(List.of());

        assertThatCode(service::getAllPropertiesWithStats).doesNotThrowAnyException();
        PropertyStatsDTO dto = service.getAllPropertiesWithStats().get(0);
        assertThat(dto.getPropertyCount()).isZero();
        assertThat(dto.getRevenueAtCapacity()).isEqualByComparingTo("0");
    }

    private Property newProperty() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn("Test Property");
        return property;
    }
}
