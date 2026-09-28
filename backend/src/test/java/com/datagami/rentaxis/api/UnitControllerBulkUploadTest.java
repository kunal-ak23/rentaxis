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
        when(service.bulkCreateUnits(eq(propertyId), eq(null), any(), any())).thenAnswer(inv -> inv.getArgument(2));

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<List<Unit>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).bulkCreateUnits(eq(propertyId), eq(null), captor.capture(), eq(List.of(2, 3)));
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
        verify(service, never()).bulkCreateUnits(any(), any(), any(), any());
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
        verify(service, never()).bulkCreateUnits(any(), any(), any(), any());
    }

    @Test
    void emptyFile_returns400WithMessage() {
        ResponseEntity<?> res = controller.bulkUploadUnits(csv(""), propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("message")).isEqualTo("CSV file is empty");
        verify(service, never()).bulkCreateUnits(any(), any(), any(), any());
    }

    /**
     * Break-it R3 ops3 F2 / data3 F5: the rules Add Unit applies hold per row — a
     * negative size or rent, and a rent with a third decimal (never rounded), are row
     * errors and nothing reaches the service.
     */
    @Test
    void negativeSizeNegativeRentAndThirdDecimal_areRowErrors() {
        MockMultipartFile file = csv("""
                unitNumber,type,sizeSqft,expectedRent,status
                OPS-B101,BHK1,-50,-9000,VACANT
                OPS-B102,BHK1,850,1000.555,VACANT
                OPS-B103,BHK1,0,-5000,VACANT
                OPS-B104,BHK1,850,1000.50,VACANT
                """);

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("errors")).isEqualTo(List.of(
                "Row 2: Size must be greater than 0",
                "Row 2: Expected rent: Amounts cannot be negative",
                "Row 3: Expected rent: Amounts can have at most 2 decimal places",
                "Row 4: Size must be greater than 0",
                "Row 4: Expected rent: Amounts cannot be negative"));
        verify(service, never()).bulkCreateUnits(any(), any(), any(), any());
    }
    /** Break-it R3 data3 F4 (review round 2): a reader failure never echoes the exception text. */
    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableFileIsASentenceWithAReferenceNotTheExceptionText() throws Exception {
        org.springframework.web.multipart.MultipartFile file = org.mockito.Mockito.mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getInputStream()).thenThrow(new java.io.IOException("/var/lib/rentaxis/tmp/upload_7f3a.tmp (Permission denied)"));

        ResponseEntity<?> res = controller.bulkUploadUnits(file, propertyId, null);

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        String message = String.valueOf(((Map<String, Object>) res.getBody()).get("message"));
        assertThat(message).startsWith("The file could not be read as a CSV").containsPattern("reference [0-9A-F]{8}$");
        assertThat(message).doesNotContain("/var/lib").doesNotContain("Permission denied");
        verify(service, never()).bulkCreateUnits(any(), any(), any(), any());
    }
}
