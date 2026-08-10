package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreatePropertyDTO;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests the DTO-to-entity mapping in {@link PropertyController#createProperty} —
 * in particular {@code fixedExpenses}, which the web "Add Project" form submits
 * and which used to be silently dropped because the controller never mapped it.
 */
@ExtendWith(MockitoExtension.class)
class PropertyControllerCreateTest {

    @Mock
    PropertyService service;

    PropertyController controller;

    @BeforeEach
    void setUp() {
        controller = new PropertyController(service);
        when(service.createProperty(any(Property.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreatePropertyDTO baseDto() {
        CreatePropertyDTO dto = new CreatePropertyDTO();
        dto.setNameEn("Marina Tower");
        dto.setNameAr("برج المارينا");
        dto.setType(PropertyType.RESIDENTIAL);
        dto.setEmirate(Emirate.DUBAI);
        dto.setAddress("Dubai Marina");
        dto.setMakaniNumber("12345-67890");
        return dto;
    }

    @Test
    void createProperty_mapsFixedExpensesFromDto() {
        CreatePropertyDTO dto = baseDto();
        dto.setFixedExpenses(new BigDecimal("2500.50"));

        Property created = controller.createProperty(dto).getBody();

        assertThat(created).isNotNull();
        assertThat(created.getFixedExpenses()).isEqualByComparingTo("2500.50");
    }

    @Test
    void createProperty_nullFixedExpenses_keepsEntityDefaultZero() {
        Property created = controller.createProperty(baseDto()).getBody();

        assertThat(created).isNotNull();
        assertThat(created.getFixedExpenses()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void createProperty_mapsAllScalarFields() {
        Property created = controller.createProperty(baseDto()).getBody();

        assertThat(created).isNotNull();
        assertThat(created.getNameEn()).isEqualTo("Marina Tower");
        assertThat(created.getNameAr()).isEqualTo("برج المارينا");
        assertThat(created.getType()).isEqualTo(PropertyType.RESIDENTIAL);
        assertThat(created.getEmirate()).isEqualTo(Emirate.DUBAI);
        assertThat(created.getAddress()).isEqualTo("Dubai Marina");
        assertThat(created.getMakaniNumber()).isEqualTo("12345-67890");
    }
}
