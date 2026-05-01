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
                "10000", "BD-001", "2026-02-15", "Emirates NBD");

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
        addRow(sheet, 1, "Marina Heights", "102", "sara@email.com",
                "1", "2026-03-01", "2026-03-01", "CHQ-1001", "Emirates NBD", "21250", "CHEQUE");
        addRow(sheet, 2, "Marina Heights", "102", "sara@email.com",
                "2", "2026-06-01", "2026-06-01", "CHQ-1002", "Emirates NBD", "21250", "CHEQUE");
        addRow(sheet, 3, "Marina Heights", "102", "sara@email.com",
                "3", "2026-09-01", "2026-09-01", "CHQ-1003", "Emirates NBD", "21250", "CHEQUE");
        addRow(sheet, 4, "Marina Heights", "102", "sara@email.com",
                "4", "2026-12-01", "2026-12-01", "CHQ-1004", "Emirates NBD", "21250", "CHEQUE");

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
