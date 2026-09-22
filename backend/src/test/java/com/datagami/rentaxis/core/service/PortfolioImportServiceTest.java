package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PortfolioImportServiceTest {

    @Mock ImportJobRepository importJobRepository;
    @Mock PropertyRepository propertyRepository;
    @Mock RenterRepository renterRepository;
    @Mock PortfolioImportPersistService persistService;
    // The v2 cut-over collaborators: this test only exercises v1 workbooks (no
    // Contracts sheet), so neither is reached — but the constructor takes them.
    @Mock com.datagami.rentaxis.core.service.cutover.ContractImportValidator contractValidator;
    @Mock com.datagami.rentaxis.core.service.cutover.ContractImportPersistService contractPersistService;

    PortfolioImportService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioImportService(importJobRepository, propertyRepository, renterRepository,
                persistService, contractValidator, contractPersistService);
        // validateDbConflicts (called by validateAll) now uses the explicit
        // tenant-scoped repository methods and refuses to run without a
        // tenant context — set one up so the validator can proceed.
        TenantContextHolder.setTenantId(UUID.randomUUID());
        lenient().when(propertyRepository.findByTenantIdAndNameEnIn(any(), anyCollection())).thenReturn(Collections.emptyList());
        lenient().when(renterRepository.findByTenantIdAndEmailIn(any(), anyCollection())).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void oldTemplate_withoutNewColumns_parsesWithoutErrors() {
        Workbook wb = buildLegacyWorkbook();

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

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

    @Test
    void rentXor_bothSet_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "MonthlyRent", "5000");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isIn("RentAmount", "MonthlyRent");
            assertThat(e.getMessage()).containsIgnoringCase("exactly one");
        });
    }

    @Test
    void rentXor_neitherSet_isError() {
        Workbook wb = buildLegacyWorkbook();
        clearCell(wb, "Leases", 1, "RentAmount");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isIn("RentAmount", "MonthlyRent");
            assertThat(e.getMessage()).containsIgnoringCase("exactly one");
        });
    }

    @Test
    void status_invalidValue_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "Status", "PENDING");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isEqualTo("Status");
        });
    }

    @Test
    void status_blankDefaultsToActive_noError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "Status", "");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).extracting(ImportErrorDTO::getField).doesNotContain("Status");
    }

    @Test
    void status_acceptsActiveAndDraft() {
        for (String value : new String[]{"ACTIVE", "DRAFT", "active", "draft"}) {
            Workbook wb = buildLegacyWorkbook();
            setCell(wb, "Leases", 1, "Status", value);
            List<ImportErrorDTO> errors = service.validateAll(wb).errors();
            assertThat(errors).extracting(ImportErrorDTO::getField).doesNotContain("Status");
        }
    }

    @Test
    void paymentMethod_acceptsBankTransferAndCash() {
        for (String method : new String[]{"BANK_TRANSFER", "CASH", "CHEQUE", "ONLINE"}) {
            Workbook wb = buildLegacyWorkbook();
            setCell(wb, "Leases", 1, "PaymentMethod", method);
            List<ImportErrorDTO> errors = service.validateAll(wb).errors();
            assertThat(errors).extracting(ImportErrorDTO::getField).doesNotContain("PaymentMethod");
        }
    }

    @Test
    void depositPaymentMethod_invalidValue_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "DepositPaymentMethod", "BITCOIN");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isEqualTo("DepositPaymentMethod");
        });
    }

    @Test
    void bookingDeposit_partialFill_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "BookingDeposit_Amount", "10000");
        setCell(wb, "Leases", 1, "BookingDeposit_Number", "BD-1");
        setCell(wb, "Leases", 1, "BookingDeposit_Date", "2026-02-15");
        // Bank intentionally left blank.

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Leases");
            assertThat(e.getField()).isEqualTo("BookingDeposit_Amount");
            assertThat(e.getMessage()).containsIgnoringCase("together");
        });
    }

    @Test
    void bookingDeposit_zeroAmount_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "BookingDeposit_Amount", "0");
        setCell(wb, "Leases", 1, "BookingDeposit_Number", "BD-1");
        setCell(wb, "Leases", 1, "BookingDeposit_Date", "2026-02-15");
        setCell(wb, "Leases", 1, "BookingDeposit_Bank", "Emirates NBD");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getField()).isEqualTo("BookingDeposit_Amount");
            assertThat(e.getMessage()).containsIgnoringCase("> 0");
        });
    }

    @Test
    void agreementDate_unparseable_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "AgreementDate", "not-a-date");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getField()).isEqualTo("AgreementDate");
        });
    }

    @Test
    void adminFee_negative_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "AdminFee", "-100");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getField()).isEqualTo("AdminFee");
            assertThat(e.getMessage()).containsIgnoringCase("negative");
        });
    }

    @Test
    void parkingRemoteFee_nonNumeric_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "ParkingRemoteFee", "free");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> assertThat(e.getField()).isEqualTo("ParkingRemoteFee"));
    }

    @Test
    void vatToggle_invalidBool_isError() {
        Workbook wb = buildLegacyWorkbook();
        setCell(wb, "Leases", 1, "RentVatApplicable", "maybe");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> assertThat(e.getField()).isEqualTo("RentVatApplicable"));
    }

    @Test
    void vatToggle_acceptsTrueFalseYesNo10() {
        for (String value : new String[]{"true", "false", "yes", "no", "1", "0", "TRUE", ""}) {
            Workbook wb = buildLegacyWorkbook();
            setCell(wb, "Leases", 1, "RentVatApplicable", value);
            List<ImportErrorDTO> errors = service.validateAll(wb).errors();
            assertThat(errors)
                    .as("VAT toggle should accept '%s'", value)
                    .extracting(ImportErrorDTO::getField)
                    .doesNotContain("RentVatApplicable");
        }
    }

    @Test
    void chequesSheet_absent_isFine() {
        // Legacy workbook has no Cheques sheet — must validate cleanly.
        Workbook wb = buildLegacyWorkbook();
        List<ImportErrorDTO> errors = service.validateAll(wb).errors();
        assertThat(errors).extracting(ImportErrorDTO::getSheet).doesNotContain("Cheques");
    }

    @Test
    void chequesSheet_referencingMissingLease_isError() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        addChequeRow(wb, 1, "Marina Heights", "999", "ahmed@email.com",
                "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "5000", "CHEQUE");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Cheques");
            assertThat(e.getField()).isEqualTo("PropertyName");
            assertThat(e.getMessage()).containsIgnoringCase("no leases row matches");
        });
    }

    @Test
    void chequesSheet_duplicateInstallmentNo_isError() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "30000", "CHEQUE");
        addChequeRow(wb, 2, "Marina Heights", "101", "ahmed@email.com",
                "1", "2026-04-01", "2026-04-01", "C-2", "Emirates NBD", "30000", "CHEQUE");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Cheques");
            assertThat(e.getField()).isEqualTo("InstallmentNo");
            assertThat(e.getMessage()).containsIgnoringCase("duplicate");
        });
    }

    @Test
    void chequesSheet_sumNotEqualTotalRent_isError() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        // legacy lease has RentAmount=60000 → expect cheques to sum to 60000.
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "1", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "20000", "CHEQUE");
        addChequeRow(wb, 2, "Marina Heights", "101", "ahmed@email.com",
                "2", "2026-07-01", "2026-07-01", "C-2", "Emirates NBD", "20000", "CHEQUE");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Cheques");
            assertThat(e.getField()).isEqualTo("Amount");
            assertThat(e.getMessage()).containsIgnoringCase("does not match");
        });
    }

    @Test
    void chequesSheet_chequeRowMissingChequeNumber_isError_whenMethodIsCheque() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "1", "2026-01-01", "2026-01-01", "", "Emirates NBD", "60000", "CHEQUE");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Cheques");
            assertThat(e.getField()).isEqualTo("UniqueId");
        });
    }

    @Test
    void chequesSheet_cashRowOmitsBank_isFine() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "1", "2026-01-01", "", "", "", "60000", "CASH");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).filteredOn(e -> "Cheques".equals(e.getSheet())).isEmpty();
    }

    @Test
    void chequesSheet_dueDateOutsideLease_isWarningNotError() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        // single cheque covers the whole 60000 totalRent, due BEFORE startDate (2026-01-01).
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "1", "2025-12-15", "2025-12-15", "C-1", "Emirates NBD", "60000", "CHEQUE");

        PortfolioImportService.ValidationOutcome outcome = service.validateAll(wb);

        assertThat(outcome.errors())
                .extracting(ImportErrorDTO::getField)
                .as("DueDate-outside-lease must not be a hard error")
                .doesNotContain("DueDate");
        assertThat(outcome.warnings()).anySatisfy(w -> {
            assertThat(w.getSheet()).isEqualTo("Cheques");
            assertThat(w.getField()).isEqualTo("DueDate");
        });
    }

    @Test
    void chequesSheet_invalidInstallmentNo_isError() {
        Workbook wb = buildLegacyWorkbook();
        addChequesSheet(wb);
        addChequeRow(wb, 1, "Marina Heights", "101", "ahmed@email.com",
                "abc", "2026-01-01", "2026-01-01", "C-1", "Emirates NBD", "60000", "CHEQUE");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).anySatisfy(e -> {
            assertThat(e.getSheet()).isEqualTo("Cheques");
            assertThat(e.getField()).isEqualTo("InstallmentNo");
        });
    }

    @Test
    void monthlyRentOnly_isFine() {
        Workbook wb = buildLegacyWorkbook();
        clearCell(wb, "Leases", 1, "RentAmount");
        setCell(wb, "Leases", 1, "MonthlyRent", "5000");

        List<ImportErrorDTO> errors = service.validateAll(wb).errors();

        assertThat(errors).extracting(ImportErrorDTO::getField).doesNotContain("RentAmount", "MonthlyRent");
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

    /** Adds an empty Cheques sheet with the standard header to an in-memory workbook. */
    static void addChequesSheet(Workbook wb) {
        Sheet sheet = wb.createSheet("Cheques");
        writeRow(sheet, 0,
                "PropertyName", "UnitNumber", "RenterEmail",
                "InstallmentNo", "DueDate", "ChequeOrPaymentDate",
                "UniqueId", "Bank", "Amount", "Method");
    }

    static void addChequeRow(Workbook wb, int rowIdx,
                             String propertyName, String unitNumber, String renterEmail,
                             String installmentNo, String dueDate, String chequeOrPaymentDate,
                             String uniqueId, String bank, String amount, String method) {
        Sheet sheet = wb.getSheet("Cheques");
        writeRow(sheet, rowIdx,
                propertyName, unitNumber, renterEmail,
                installmentNo, dueDate, chequeOrPaymentDate,
                uniqueId, bank, amount, method);
    }
}
