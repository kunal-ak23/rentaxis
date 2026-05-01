package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PortfolioImportServiceTest {

    @Mock ImportJobRepository importJobRepository;
    @Mock PropertyRepository propertyRepository;
    @Mock RenterRepository renterRepository;
    @Mock PortfolioImportPersistService persistService;

    PortfolioImportService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioImportService(importJobRepository, propertyRepository, renterRepository, persistService);
        lenient().when(propertyRepository.findByNameEnIn(anyCollection())).thenReturn(Collections.emptyList());
        lenient().when(renterRepository.findByEmailIn(anyCollection())).thenReturn(Collections.emptyList());
    }

    @Test
    void oldTemplate_withoutNewColumns_parsesWithoutErrors() {
        Workbook wb = buildLegacyWorkbook();

        List<ImportErrorDTO> errors = service.validateWorkbook(wb);

        assertThat(errors)
                .as("legacy 10-column Leases sheet must not surface errors about new headers")
                .extracting(ImportErrorDTO::getField)
                .doesNotContain(
                        "MonthlyRent", "Status", "AdminFee", "ParkingRemoteFee",
                        "RentVatApplicable", "AdminFeeVatApplicable",
                        "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable",
                        "DepositPaymentMethod", "AgreementDate",
                        "BookingDeposit_Amount", "BookingDeposit_Number",
                        "BookingDeposit_Date", "BookingDeposit_Bank");
    }

    // ----- Test helpers -----

    /** Builds the original 4-sheet, 10-column Leases workbook (no new columns, no Cheques sheet). */
    static Workbook buildLegacyWorkbook() {
        Workbook wb = new XSSFWorkbook();

        Sheet props = wb.createSheet("Properties");
        writeRow(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber");
        writeRow(props, 1, "Marina Heights", "", "DUBAI", "Dubai Marina", "RESIDENTIAL", "");

        Sheet units = wb.createSheet("Units");
        writeRow(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        writeRow(units, 1, "Marina Heights", "Tower A", "101", "BHK1", "850", "60000");

        Sheet renters = wb.createSheet("Renters");
        writeRow(renters, 0, "Name", "NameAr", "Email", "Phone");
        writeRow(renters, 1, "Ahmed Ali", "", "ahmed@email.com", "+971501234567");

        Sheet leases = wb.createSheet("Leases");
        writeRow(leases, 0,
                "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "StartDate", "EndDate",
                "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber");
        writeRow(leases, 1,
                "Marina Heights", "Tower A", "101", "ahmed@email.com",
                "2026-01-01", "2026-12-31",
                "60000", "5000", "12", "CHEQUE", "EJ-2026-001");
        return wb;
    }

    static void writeRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    /** Set or clear a named cell in an existing data row, looking up the column by header name. */
    static void setCell(Workbook wb, String sheetName, int rowIdx, String header, String value) {
        Sheet sheet = wb.getSheet(sheetName);
        Row hdr = sheet.getRow(0);
        int col = -1;
        for (int c = 0; c < hdr.getLastCellNum(); c++) {
            Cell h = hdr.getCell(c);
            if (h != null && header.equalsIgnoreCase(h.getStringCellValue().trim())) {
                col = c;
                break;
            }
        }
        if (col < 0) {
            // Header missing — append it and write into the new column.
            col = hdr.getLastCellNum();
            hdr.createCell(col).setCellValue(header);
        }
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        row.createCell(col).setCellValue(value);
    }

    /** Clear a named cell in a data row by writing an empty string. */
    static void clearCell(Workbook wb, String sheetName, int rowIdx, String header) {
        setCell(wb, sheetName, rowIdx, header, "");
    }
}
