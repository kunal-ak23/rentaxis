package com.datagami.rentaxis.core.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class PortfolioTemplateService {

    public byte[] generateTemplate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            createPropertiesSheet(workbook, headerStyle);
            createUnitsSheet(workbook, headerStyle);
            createRentersSheet(workbook, headerStyle);
            createLeasesSheet(workbook, headerStyle);
            createChequesSheet(workbook, headerStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * The accounting-v2 cut-over workbook (spec §10.3 step 1): every live contract a
     * landlord is bringing off PACT, with its lines, its instruments and the ledger
     * accounts each building posts to.
     *
     * <p>A separate builder rather than a flag on {@link #generateTemplate()}, and
     * its own Properties / Units / Renters sheets rather than the v1 ones, for a
     * reason worth stating: <b>this workbook has to import as it stands</b>. Its
     * sample contract references its sample unit, which references its sample
     * property, and the cheque rows add up to the contract's lines to the fils —
     * so an accountant can download it, upload it unchanged, and see what a
     * cut-over produces before typing a single row of their own. Sharing the v1
     * sheets would have put the v1 sample property on the Properties sheet and
     * "Sample Tower" on the Contracts sheet, and the first thing the template did
     * would be to fail its own validation.</p>
     *
     * <p>The v1 template is therefore untouched, down to the byte.</p>
     */
    public byte[] generateCutOverTemplate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle headerStyle = createHeaderStyle(workbook);

            createCutOverPropertiesSheet(workbook, headerStyle);
            createCutOverUnitsSheet(workbook, headerStyle);
            createCutOverRentersSheet(workbook, headerStyle);
            createContractsSheet(workbook, headerStyle);
            createContractChequesSheet(workbook, headerStyle);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * v1's six columns, then the six account names a building's postings need.
     *
     * <p>Four of them are the columns of the client's own "property mapping ledgers"
     * export, under the names it uses; the last two are the further roles a lease
     * must have mapped before it can post (spec §5.4), here so the accountant
     * supplies them in the same pass. A blank one is not a failure — the tenant's
     * account template fills it — but it is reported, because a role filled by
     * guesswork routes a whole tower's rent into another tower's ledger.</p>
     */
    private void createCutOverPropertiesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Properties");
        String[] headers = {"PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount",
                "BankAccount", "PdcReceivableAccount", "SecurityDepositAccount"};
        createHeaderRow(sheet, headers, headerStyle);
        addDropdown(sheet, 1, 100, 2, 2, new String[]{"DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN",
                "RAS_AL_KHAIMAH", "FUJAIRAH", "UMM_AL_QUWAIN"});
        addDropdown(sheet, 1, 100, 4, 4, new String[]{"RESIDENTIAL", "COMMERCIAL", "MIXED"});

        addRow(sheet, 1, "Sample Tower", "", "DUBAI", "Sample Street, Sample District", "RESIDENTIAL", "",
                "Rental Income ST1", "Rent Receivable - ST1", "Advance Rent - ST1",
                "Sample Bank - ST1", "PDC Receivable ST1", "Security Deposit ST1");

        autoSizeColumns(sheet, headers.length);
    }

    private void createCutOverUnitsSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Units");
        String[] headers = {"PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent"};
        createHeaderRow(sheet, headers, headerStyle);
        addDropdown(sheet, 1, 1000, 3, 3,
                new String[]{"STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"});
        addRow(sheet, 1, "Sample Tower", "", "A-101", "BHK1", "850", "51000");
        addRow(sheet, 2, "Sample Tower", "", "A-102", "OFFICE", "1200", "21000");
        autoSizeColumns(sheet, headers.length);
    }

    private void createCutOverRentersSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Renters");
        String[] headers = {"Name", "NameAr", "Email", "Phone"};
        createHeaderRow(sheet, headers, headerStyle);
        addRow(sheet, 1, "Sample Renter One", "", "sample.renter.one@example.com", "+971500000000");
        addRow(sheet, 2, "Sample Renter Two", "", "sample.renter.two@example.com", "+971500000001");
        autoSizeColumns(sheet, headers.length);
    }

    /**
     * One row per contract <em>line</em>. Rows sharing a ContractNumber are one
     * contract, and only the first carries the header fields — which is how PACT's
     * own listings print, and how an export pasted in will already be shaped.
     *
     * <p>{@code CreditAccount} is left blank in the sample deliberately. A RENT line
     * credits ADVANCE_RENT, not rental income — rent is unearned on the day the
     * contract posts and is recognised day by day afterwards (spec §6.1) — so the
     * account it wants is the one the Properties sheet already named for that role.
     * The column is for the exception, not the rule, and a name of the wrong type
     * for the line's charge type is refused with the cell reference.</p>
     */
    private void createContractsSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Contracts");
        String[] headers = {
                "ContractNumber", "EjariNumber", "PropertyName", "BuildingName", "UnitNumber",
                "RenterEmail", "ContractDate", "StartDate", "EndDate", "GracePeriodDays",
                "LineNo", "ChargeTypeCode", "CreditAccount", "GrossAmount", "DiscountAmount",
                "VatApplicable", "Narration"};
        createHeaderRow(sheet, headers, headerStyle);
        addDropdown(sheet, 1, 5000, 15, 15, new String[]{"false", "true"});

        addRow(sheet, 1, "SAMPLE-0001", "EJ-2026-0001", "Sample Tower", "", "A-101",
                "sample.renter.one@example.com",
                "2026-09-11", "2026-09-24", "2027-09-23", "5",
                "1", "RENT", "", "51000.00", "0", "false", "Annual rent");
        addRow(sheet, 2, "SAMPLE-0001", "", "", "", "", "", "", "", "", "",
                "2", "SECURITY_DEPOSIT", "", "5000.00", "0", "false", "Security deposit");
        // A second contract that charges VAT, so the template shows what a commercial
        // letting looks like and its cheque is the GROSS: 21,000 + 5% = 22,050.
        addRow(sheet, 3, "SAMPLE-0002", "EJ-2026-0002", "Sample Tower", "", "A-102",
                "sample.renter.two@example.com",
                "2026-09-11", "2026-10-01", "2027-09-30", "",
                "1", "RENT", "", "21000.00", "0", "true", "Annual rent (VAT applicable)");

        autoSizeColumns(sheet, headers.length);
    }

    /**
     * The instruments, and what had already happened to each one on the day the
     * books were cut over.
     *
     * <p>Σ of the Amount column must equal the contract's lines <em>including
     * VAT</em>, to the fils — the sample's 31,000 + 25,000 against 51,000 + 5,000 —
     * because that is the rule the contract posting itself enforces. The three date
     * columns are what let the bulk post replay a cheque's history on the days it
     * really happened rather than on the day of the import.</p>
     */
    private void createContractChequesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Cheques");
        String[] headers = {
                "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate",
                "PayeeBank", "DebitAccount", "Amount", "Narration", "Mode", "Status",
                "DepositedDate", "ClearedDate", "BouncedDate"};
        createHeaderRow(sheet, headers, headerStyle);
        addDropdown(sheet, 1, 5000, 9, 9, new String[]{"PDC", "CASH", "TRANSFER"});
        addDropdown(sheet, 1, 5000, 10, 10, new String[]{"REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED"});

        addRow(sheet, 1, "SAMPLE-0001", "1", "2026-09-11", "100001", "2026-09-24", "Sample Bank", "",
                "31000.00", "Rent - 1st Installment", "PDC", "CLEARED", "", "2026-09-25", "");
        addRow(sheet, 2, "SAMPLE-0001", "2", "2026-09-11", "100002", "2027-03-24", "Sample Bank", "",
                "25000.00", "Rent - 2nd Installment", "PDC", "REGISTERED", "", "", "");
        addRow(sheet, 3, "SAMPLE-0002", "1", "2026-09-11", "100003", "2026-10-01", "Sample Bank", "",
                "22050.00", "Rent - 1st Installment", "PDC", "REGISTERED", "", "", "");

        autoSizeColumns(sheet, headers.length);
    }

    private void createPropertiesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Properties");
        String[] headers = {"PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber"};
        createHeaderRow(sheet, headers, headerStyle);

        // Dropdown: Emirate (column C)
        String[] emirates = {"DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN", "RAS_AL_KHAIMAH", "FUJAIRAH", "UMM_AL_QUWAIN"};
        addDropdown(sheet, 1, 100, 2, 2, emirates);

        // Dropdown: Type (column E)
        String[] types = {"RESIDENTIAL", "COMMERCIAL", "MIXED"};
        addDropdown(sheet, 1, 100, 4, 4, types);

        // Example rows
        addRow(sheet, 1, "Marina Heights", "مارينا هايتس", "DUBAI", "Dubai Marina, Plot 45", "RESIDENTIAL", "12345-67890");
        addRow(sheet, 2, "Business Central", "بزنس سنترال", "DUBAI", "Business Bay, Tower Rd", "COMMERCIAL", "98765-43210");
        addRow(sheet, 3, "Sharjah Residences", "شارقة ريزيدنسز", "SHARJAH", "Al Majaz 3", "RESIDENTIAL", "");

        autoSizeColumns(sheet, headers.length);
    }

    private void createUnitsSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Units");
        String[] headers = {"PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent"};
        createHeaderRow(sheet, headers, headerStyle);

        // Dropdown: UnitType (column D)
        String[] unitTypes = {"STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"};
        addDropdown(sheet, 1, 100, 3, 3, unitTypes);

        // Example rows
        addRow(sheet, 1, "Marina Heights", "Tower A", "101", "BHK1", "850", "60000");
        addRow(sheet, 2, "Marina Heights", "Tower A", "102", "BHK2", "1200", "85000");
        addRow(sheet, 3, "Marina Heights", "Tower B", "201", "STUDIO", "500", "40000");
        addRow(sheet, 4, "Business Central", "", "G01", "OFFICE", "2000", "150000");

        autoSizeColumns(sheet, headers.length);
    }

    private void createRentersSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Renters");
        String[] headers = {"Name", "NameAr", "Email", "Phone"};
        createHeaderRow(sheet, headers, headerStyle);

        // Example rows
        addRow(sheet, 1, "Ahmed Ali", "أحمد علي", "ahmed@email.com", "+971501234567");
        addRow(sheet, 2, "Sara Khan", "سارة خان", "sara@email.com", "+971509876543");
        addRow(sheet, 3, "Mohammed Hassan", "محمد حسن", "mohammed@email.com", "+971551234567");

        autoSizeColumns(sheet, headers.length);
    }

    private void createLeasesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Leases");
        String[] headers = {
                "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
                "StartDate", "EndDate",
                "RentAmount", "DepositAmount", "PaymentTerms", "PaymentMethod", "EjariNumber",
                // Lease-agreement fields (added 2026-05-02):
                "MonthlyRent",
                "AdminFee", "ParkingRemoteFee",
                "RentVatApplicable", "AdminFeeVatApplicable",
                "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable",
                "DepositPaymentMethod", "AgreementDate", "Status",
                "BookingDeposit_Amount", "BookingDeposit_Number", "BookingDeposit_Date", "BookingDeposit_Bank"
        };
        createHeaderRow(sheet, headers, headerStyle);

        // Dropdown: PaymentMethod (column J / index 9), DepositPaymentMethod (column S / index 18)
        String[] methods = {"CHEQUE", "BANK_TRANSFER", "ONLINE", "CASH"};
        addDropdown(sheet, 1, 1000, 9, 9, methods);
        addDropdown(sheet, 1, 1000, 18, 18, methods);

        // Dropdown: Status (column U / index 20)
        String[] statuses = {"ACTIVE", "DRAFT"};
        addDropdown(sheet, 1, 1000, 20, 20, statuses);

        // Example rows
        addRow(sheet, 1, "Marina Heights", "Tower A", "101", "ahmed@email.com", "2026-01-01", "2026-12-31",
                "60000", "5000", "12", "CHEQUE", "EJ-2026-001",
                "", "0", "0",
                "", "", "", "",
                "", "", "ACTIVE",
                "", "", "", "");
        addRow(sheet, 2, "Marina Heights", "Tower A", "102", "sara@email.com", "2026-03-01", "2027-02-28",
                "", "10000", "4", "CHEQUE", "EJ-2026-002",
                "7000", "500", "100",
                "", "", "", "",
                "BANK_TRANSFER", "2026-02-15", "DRAFT",
                "10000", "BD-001", "2026-02-15", "Sample Bank");

        autoSizeColumns(sheet, headers.length);
    }

    private void createChequesSheet(XSSFWorkbook workbook, CellStyle headerStyle) {
        XSSFSheet sheet = workbook.createSheet("Cheques");
        String[] headers = {
                "PropertyName", "UnitNumber", "RenterEmail",
                "InstallmentNo", "DueDate", "ChequeOrPaymentDate",
                "UniqueId", "Bank", "Amount", "Method"
        };
        createHeaderRow(sheet, headers, headerStyle);

        // Dropdown: Method (column J / index 9)
        String[] methods = {"CHEQUE", "BANK_TRANSFER", "ONLINE", "CASH"};
        addDropdown(sheet, 1, 1000, 9, 9, methods);

        // Example rows tied to the second Leases example row (sara@email.com, 4 cheques over 12 months).
        // The Amounts are the rent instalments the renter writes, VAT included when
        // the rent carries it, and must add up to the rent exactly — MonthlyRent
        // 7,000 × 12 = 84,000 here (the validator is exact, like the post). With a
        // Cheques sheet the lease's rent IS this sum; the deposit and fees get
        // cheque rows of their own, generated by the import.
        addRow(sheet, 1, "Marina Heights", "102", "sara@email.com",
                "1", "2026-03-01", "2026-03-01", "CHQ-1001", "Sample Bank", "21000", "CHEQUE");
        addRow(sheet, 2, "Marina Heights", "102", "sara@email.com",
                "2", "2026-06-01", "2026-06-01", "CHQ-1002", "Sample Bank", "21000", "CHEQUE");
        addRow(sheet, 3, "Marina Heights", "102", "sara@email.com",
                "3", "2026-09-01", "2026-09-01", "CHQ-1003", "Sample Bank", "21000", "CHEQUE");
        addRow(sheet, 4, "Marina Heights", "102", "sara@email.com",
                "4", "2026-12-01", "2026-12-01", "CHQ-1004", "Sample Bank", "21000", "CHEQUE");

        autoSizeColumns(sheet, headers.length);
    }

    private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private void createHeaderRow(XSSFSheet sheet, String[] headers, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    private void addRow(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }

    private void addDropdown(XSSFSheet sheet, int firstRow, int lastRow, int firstCol, int lastCol, String[] values) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidationConstraint constraint = helper.createExplicitListConstraint(values);
        CellRangeAddressList range = new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol);
        DataValidation validation = helper.createValidation(constraint, range);
        validation.setShowErrorBox(true);
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("Invalid Value", "Please select a value from the dropdown list.");
        sheet.addValidationData(validation);
    }

    private void autoSizeColumns(XSSFSheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
