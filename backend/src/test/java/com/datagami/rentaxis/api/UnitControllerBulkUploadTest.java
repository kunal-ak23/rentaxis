package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.UnitService;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the CSV parsing contract of {@code POST /api/v1/units/bulk}:
 * malformed rows (bad enum / bad number) must produce a 400 with row-level
 * error messages, not a bodyless 500.
 */
@ExtendWith(MockitoExtension.class)
class UnitControllerBulkUploadTest {

    @Mock
    UnitService service;

    UnitController controller;

    private final UUID propertyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new UnitController(service);
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "units.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validCsv_parsesRowsAndReturns200() {
        MockMultipartFile file = csv("""
                unitNumber,type,sizeSqft,expectedRent,status
                101,BHK1,850,55000,VACANT
                102,STUDIO,,,
                """);
        when(service.bulkCreateUnits(eq(propertyId), eq(null), any())).thenAnswer(inv -> inv.getArgument(2));

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<List<Unit>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).bulkCreateUnits(eq(propertyId), eq(null), captor.capture());
        List<Unit> units = captor.getValue();
        assertThat(units).hasSize(2);
        assertThat(units.get(0).getUnitNumber()).isEqualTo("101");
        assertThat(units.get(0).getType()).isEqualTo(UnitType.BHK1);
        assertThat(units.get(0).getSizeSqft()).isEqualByComparingTo(new BigDecimal("850"));
        assertThat(units.get(0).getExpectedRent()).isEqualByComparingTo(new BigDecimal("55000"));
        assertThat(units.get(0).getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(units.get(1).getUnitNumber()).isEqualTo("102");
        assertThat(units.get(1).getType()).isEqualTo(UnitType.STUDIO);
    }

    @Test
    void invalidEnumAndNumber_returns400WithRowLevelErrors() {
        MockMultipartFile file = csv("""
                unitNumber,type,sizeSqft,expectedRent,status
                101,BHK4,850,55000,VACANT
                102,BHK2,not-a-number,55000,VACANT
                103,BHK1,900,60000,SOMEDAY
                """);

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("CSV contains invalid rows");
        assertThat(body.get("errors")).isEqualTo(List.of(
                "Row 2: Invalid unit type 'BHK4'",
                "Row 3: Invalid SizeSqft 'not-a-number'",
                "Row 4: Invalid status 'SOMEDAY'"));
        verify(service, never()).bulkCreateUnits(any(), any(), any());
    }

    @Test
    void missingUnitNumber_returns400() {
        MockMultipartFile file = csv("""
                unitNumber,type,sizeSqft,expectedRent,status
                ,BHK1,850,55000,VACANT
                """);

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("errors")).isEqualTo(List.of("Row 2: UnitNumber is required"));
        verify(service, never()).bulkCreateUnits(any(), any(), any());
    }

    @Test
    void emptyFile_returns400WithMessage() {
        ResponseEntity<?> res = controller.bulkUploadUnits(csv(""), propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("CSV file is empty");
        verify(service, never()).bulkCreateUnits(any(), any(), any());
    }
}
