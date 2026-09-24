package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cutover.ContractImportPersistService;
import com.datagami.rentaxis.core.service.cutover.ContractImportValidator;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioImportService {

    private final ImportJobRepository importJobRepository;
    private final PropertyRepository propertyRepository;
    private final RenterRepository renterRepository;
    private final PortfolioImportPersistService persistService;
    private final ContractImportValidator contractValidator;
    private final ContractImportPersistService contractPersistService;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // --- Validation Phase (no DB writes) ---

    /**
     * Full validation pass. Returns hard errors (block import) and warnings (informational only).
     *
     * <p>A workbook carrying a {@code Contracts} sheet is an accounting-v2 cut-over
     * import (spec §10.3) and goes to {@link ContractImportValidator}: same job row,
     * same async executor, same {@link ImportErrorDTO} shape and the same polling
     * endpoint — only the sheet set and the persist target differ. There is one
     * importer, with two sheet dialects, rather than two importers.</p>
     */
    public ValidationOutcome validateAll(Workbook workbook) {
        if (ContractImportValidator.isV2Workbook(workbook)) {
            return contractValidator.validate(workbook);
        }
        List<ImportErrorDTO> errors = new ArrayList<>();
        List<ImportErrorDTO> warnings = new ArrayList<>();

        Sheet propertiesSheet = workbook.getSheet("Properties");
        Sheet unitsSheet = workbook.getSheet("Units");
        Sheet rentersSheet = workbook.getSheet("Renters");
        Sheet leasesSheet = workbook.getSheet("Leases");

        if (propertiesSheet == null) errors.add(ImportErrorDTO.file("Properties", "Sheet", "Sheet 'Properties' is missing"));
        if (unitsSheet == null) errors.add(ImportErrorDTO.file("Units", "Sheet", "Sheet 'Units' is missing"));
        if (rentersSheet == null) errors.add(ImportErrorDTO.file("Renters", "Sheet", "Sheet 'Renters' is missing"));
        if (leasesSheet == null) errors.add(ImportErrorDTO.file("Leases", "Sheet", "Sheet 'Leases' is missing"));

        if (!errors.isEmpty()) return new ValidationOutcome(errors, warnings);

        // Collect data for cross-sheet validation
        Set<String> propertyNames = new HashSet<>();
        Map<String, Set<String>> unitsByProperty = new HashMap<>(); // propertyName -> set of "buildingName|unitNumber" composite keys
        Set<String> renterEmails = new HashSet<>();
        Map<String, LeaseRowSummary> leaseIndex = new LinkedHashMap<>();

        // Validate Properties sheet
        validatePropertiesSheet(propertiesSheet, errors, propertyNames);

        // Validate Units sheet
        validateUnitsSheet(unitsSheet, errors, propertyNames, unitsByProperty);

        // Validate Renters sheet
        validateRentersSheet(rentersSheet, errors, renterEmails);

        // Validate Leases sheet (cross-sheet refs)
        validateLeasesSheet(leasesSheet, errors, propertyNames, unitsByProperty, renterEmails, leaseIndex,
                commercialProperties(propertiesSheet));

        // Validate optional Cheques sheet against the lease index
        Sheet chequesSheet = workbook.getSheet("Cheques");
        if (chequesSheet != null) {
            validateChequesSheet(chequesSheet, leaseIndex, errors, warnings);
        }

        // Check DB conflicts
        validateDbConflicts(propertyNames, renterEmails, errors);

        return new ValidationOutcome(errors, warnings);
    }

    public record ValidationOutcome(List<ImportErrorDTO> errors, List<ImportErrorDTO> warnings) {}

    /** Summary of a Leases row, captured during validation, used by the Cheques-sheet checks. */
    record LeaseRowSummary(String paymentMethod, BigDecimal totalRent,
                           LocalDate startDate, LocalDate endDate,
                           BigDecimal monthlyRent, long months, boolean rentVat) {
        LeaseRowSummary(String paymentMethod, BigDecimal totalRent, LocalDate startDate, LocalDate endDate) {
            this(paymentMethod, totalRent, startDate, endDate, null, 0, false);
        }
    }

    /**
     * Lower-cased names of the sheet's COMMERCIAL properties — where
     * RentVatApplicable defaults to true, as the persist phase reads it.
     */
    private Set<String> commercialProperties(Sheet sheet) {
        Set<String> out = new HashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            if ("COMMERCIAL".equals(getCellString(row, 4).trim().toUpperCase().replace(" ", "_"))) {
                out.add(getCellString(row, 0).toLowerCase());
            }
        }
        return out;
    }

    private void validatePropertiesSheet(Sheet sheet, List<ImportErrorDTO> errors, Set<String> propertyNames) {
        Set<String> validEmirates = Arrays.stream(Emirate.values()).map(Enum::name).collect(Collectors.toSet());
        Set<String> validTypes = Arrays.stream(PropertyType.values()).map(Enum::name).collect(Collectors.toSet());

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = i + 1; // 1-based for user display

            String nameEn = getCellString(row, 0);
            String emirate = getCellString(row, 2);
            String type = getCellString(row, 4);

            if (nameEn.isEmpty()) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "PropertyName", "Property name is required"));
            } else if (!propertyNames.add(nameEn)) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "PropertyName", "Duplicate property name: " + nameEn));
            }

            if (emirate.isEmpty()) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "Emirate", "Emirate is required"));
            } else if (!validEmirates.contains(emirate.toUpperCase())) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "Emirate", "Invalid emirate: " + emirate + ". Valid: " + validEmirates));
            }

            if (type.isEmpty()) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "Type", "Type is required"));
            } else if (!validTypes.contains(type.toUpperCase())) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "Type", "Invalid type: " + type + ". Valid: " + validTypes));
            }
        }
    }

    private void validateUnitsSheet(Sheet sheet, List<ImportErrorDTO> errors, Set<String> propertyNames, Map<String, Set<String>> unitsByProperty) {
        Set<String> validUnitTypes = Arrays.stream(UnitType.values()).map(Enum::name).collect(Collectors.toSet());

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String propertyName = getCellString(row, 0);
            String buildingName = getCellString(row, 1);
            String unitNumber = getCellString(row, 2);
            String unitType = getCellString(row, 3);
            String sizeSqft = getCellString(row, 4);
            String expectedRent = getCellString(row, 5);

            if (propertyName.isEmpty()) {
                errors.add(new ImportErrorDTO("Units", rowNum, "PropertyName", "Property name is required"));
            } else if (!propertyNames.contains(propertyName)) {
                errors.add(new ImportErrorDTO("Units", rowNum, "PropertyName", "Property '" + propertyName + "' not found in Properties sheet"));
            }

            if (unitNumber.isEmpty()) {
                errors.add(new ImportErrorDTO("Units", rowNum, "UnitNumber", "Unit number is required"));
            } else if (!propertyName.isEmpty()) {
                // Composite key: buildingName|unitNumber — allows same unit number in different buildings
                // Lowercase both so casing differences across sheets don't cause false mismatches
                String compositeKey = buildingName.toLowerCase() + "|" + unitNumber.toLowerCase();
                Set<String> units = unitsByProperty.computeIfAbsent(propertyName.toLowerCase(), k -> new HashSet<>());
                if (!units.add(compositeKey)) {
                    errors.add(new ImportErrorDTO("Units", rowNum, "UnitNumber", "Duplicate unit number '" + unitNumber + "' in building '" + buildingName + "' of property '" + propertyName + "'"));
                }
            }

            if (!unitType.isEmpty() && !validUnitTypes.contains(unitType.toUpperCase())) {
                errors.add(new ImportErrorDTO("Units", rowNum, "UnitType", "Invalid unit type: " + unitType + ". Valid: " + validUnitTypes));
            }

            if (!sizeSqft.isEmpty()) {
                try { Double.parseDouble(sizeSqft); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Units", rowNum, "SizeSqft", "Size must be numeric"));
                }
            }

            if (!expectedRent.isEmpty()) {
                try { Double.parseDouble(expectedRent); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Units", rowNum, "ExpectedRent", "Expected rent must be numeric"));
                }
            }
        }
    }

    private void validateRentersSheet(Sheet sheet, List<ImportErrorDTO> errors, Set<String> renterEmails) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String name = getCellString(row, 0);
            String email = getCellString(row, 2);

            if (name.isEmpty()) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Name", "Renter name is required"));
            }

            if (email.isEmpty()) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Email is required"));
            } else if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Invalid email format: " + email));
            } else if (!renterEmails.add(email.toLowerCase())) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Duplicate email: " + email));
            }
        }
    }

    private void validateLeasesSheet(Sheet sheet, List<ImportErrorDTO> errors,
                                     Set<String> propertyNames, Map<String, Set<String>> unitsByProperty,
                                     Set<String> renterEmails,
                                     Map<String, LeaseRowSummary> leaseIndex,
                                     Set<String> commercialProperties) {
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String propertyName = cell(row, hi, "PropertyName");
            String buildingName = cell(row, hi, "BuildingName");
            String unitNumber = cell(row, hi, "UnitNumber");
            String renterEmail = cell(row, hi, "RenterEmail");
            String startDateStr = cell(row, hi, "StartDate");
            String endDateStr = cell(row, hi, "EndDate");
            String rentAmountStr = cell(row, hi, "RentAmount");
            String depositStr = cell(row, hi, "DepositAmount");
            String paymentTermsStr = cell(row, hi, "PaymentTerms");
            String paymentMethod = cell(row, hi, "PaymentMethod");

            // Cross-sheet: property+unit
            if (propertyName.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PropertyName", "Property name is required"));
            } else if (!propertyNames.contains(propertyName)) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PropertyName", "Property '" + propertyName + "' not found in Properties sheet"));
            }

            if (unitNumber.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "UnitNumber", "Unit number is required"));
            } else if (!propertyName.isEmpty()) {
                // Use composite key buildingName|unitNumber to match units validation
                String compositeKey = buildingName.toLowerCase() + "|" + unitNumber.toLowerCase();
                Set<String> units = unitsByProperty.getOrDefault(propertyName.toLowerCase(), Collections.emptySet());
                if (!units.contains(compositeKey)) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "UnitNumber",
                            "Unit '" + unitNumber + "' in building '" + buildingName + "' not found in property '" + propertyName +
                            "' on Units sheet. Ensure BuildingName matches the Units sheet (leave blank if unit has no building)."));
                }
            }

            // Cross-sheet: renter email
            if (renterEmail.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "RenterEmail", "Renter email is required"));
            } else if (!renterEmails.contains(renterEmail.toLowerCase())) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "RenterEmail", "Renter email '" + renterEmail + "' not found in Renters sheet"));
            }

            // Dates
            LocalDate startDate = null;
            LocalDate endDate = null;
            if (startDateStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "StartDate", "Start date is required"));
            } else {
                startDate = parseDate(startDateStr);
                if (startDate == null) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "StartDate", DATE_HELP));
                }
            }
            if (endDateStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "EndDate", "End date is required"));
            } else {
                endDate = parseDate(endDateStr);
                if (endDate == null) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "EndDate", DATE_HELP));
                }
            }
            if (startDate != null && endDate != null && !endDate.isAfter(startDate)) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "EndDate", "End date must be after start date"));
            }

            // Rent xor: exactly one of RentAmount or MonthlyRent must be set.
            String monthlyRentStr = cell(row, hi, "MonthlyRent");
            if (!rentAmountStr.isEmpty() && !monthlyRentStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount",
                        "Exactly one of RentAmount or MonthlyRent must be set, not both"));
            } else if (rentAmountStr.isEmpty() && monthlyRentStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount",
                        "Exactly one of RentAmount or MonthlyRent must be set"));
            } else if (!rentAmountStr.isEmpty()) {
                try { Double.parseDouble(rentAmountStr); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount", "Rent amount must be numeric"));
                }
            } else {
                try { Double.parseDouble(monthlyRentStr); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "MonthlyRent", "Monthly rent must be numeric"));
                }
            }

            // Deposit amount
            if (!depositStr.isEmpty()) {
                try { Double.parseDouble(depositStr); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "DepositAmount", "Deposit amount must be numeric"));
                }
            }

            // Payment terms
            if (!paymentTermsStr.isEmpty()) {
                try { Integer.parseInt(paymentTermsStr); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "PaymentTerms", "Payment terms must be a whole number (e.g. 12 for monthly installments)"));
                }
            }

            // Payment method
            if (!paymentMethod.isEmpty() && !VALID_PAYMENT_METHODS.contains(paymentMethod.toUpperCase())) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PaymentMethod", "Invalid payment method: " + paymentMethod + ". Valid: " + VALID_PAYMENT_METHODS));
            }

            // ---- Lease-agreement extension columns (added 2026-05-02) ----

            String depositPaymentMethod = cell(row, hi, "DepositPaymentMethod");
            if (!depositPaymentMethod.isEmpty() && !VALID_PAYMENT_METHODS.contains(depositPaymentMethod.toUpperCase())) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "DepositPaymentMethod",
                        "Invalid deposit payment method: " + depositPaymentMethod + ". Valid: " + VALID_PAYMENT_METHODS));
            }

            // Status
            String status = cell(row, hi, "Status").toUpperCase();
            if (!status.isEmpty() && !"ACTIVE".equals(status) && !"DRAFT".equals(status)) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "Status", "Status must be ACTIVE or DRAFT"));
            }

            // VAT toggles
            for (String h : VAT_TOGGLE_HEADERS) {
                String v = cell(row, hi, h).toLowerCase();
                if (!v.isEmpty() && !VALID_BOOLS.contains(v)) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, h, "Must be true/false/yes/no/1/0 or blank"));
                }
            }

            // Numeric ≥ 0 charges
            for (String h : new String[]{"AdminFee", "ParkingRemoteFee"}) {
                String v = cell(row, hi, h);
                if (v.isEmpty()) continue;
                try {
                    BigDecimal n = new BigDecimal(v);
                    if (n.signum() < 0) {
                        errors.add(new ImportErrorDTO("Leases", rowNum, h, h + " cannot be negative"));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, h, h + " must be a number"));
                }
            }

            // AgreementDate
            String agreementDate = cell(row, hi, "AgreementDate");
            if (!agreementDate.isEmpty()) {
                if (parseDate(agreementDate) == null) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "AgreementDate", "AgreementDate: " + DATE_HELP));
                }
            }

            // BookingDeposit_* — all-or-nothing; amount > 0 if any set.
            String bdAmt = cell(row, hi, "BookingDeposit_Amount");
            String bdNum = cell(row, hi, "BookingDeposit_Number");
            String bdDate = cell(row, hi, "BookingDeposit_Date");
            String bdBank = cell(row, hi, "BookingDeposit_Bank");
            boolean anyBd = !(bdAmt.isEmpty() && bdNum.isEmpty() && bdDate.isEmpty() && bdBank.isEmpty());
            boolean allBd = !bdAmt.isEmpty() && !bdNum.isEmpty() && !bdDate.isEmpty() && !bdBank.isEmpty();
            if (anyBd && !allBd) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount",
                        "All four BookingDeposit_* columns must be set together"));
            }
            if (!bdAmt.isEmpty()) {
                try {
                    BigDecimal n = new BigDecimal(bdAmt);
                    if (n.signum() <= 0) {
                        errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount",
                                "BookingDeposit_Amount must be > 0"));
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Amount",
                            "BookingDeposit_Amount must be a number"));
                }
            }
            if (!bdDate.isEmpty()) {
                if (parseDate(bdDate) == null) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Date",
                            "BookingDeposit_Date: " + DATE_HELP));
                }
            }

            // Populate leaseIndex for downstream Cheques-sheet validation when this row
            // has parseable dates and at least one rent value. Rows that are too broken
            // to summarize will already have errors logged above.
            if (startDate != null && endDate != null && endDate.isAfter(startDate)) {
                BigDecimal totalRent = computeTotalRentOrNull(rentAmountStr, monthlyRentStr, startDate, endDate);
                if (totalRent != null) {
                    String key = leaseKey(propertyName, unitNumber, renterEmail);
                    String method = paymentMethod.isEmpty() ? "CHEQUE" : paymentMethod.toUpperCase();
                    // The persist phase's reading of RentVatApplicable: blank takes the
                    // property's default (COMMERCIAL = VAT on rent).
                    String vatCell = cell(row, hi, "RentVatApplicable").trim().toLowerCase(Locale.ROOT);
                    boolean rentVat = vatCell.isEmpty()
                            ? commercialProperties.contains(propertyName.toLowerCase())
                            : Set.of("true", "yes", "1").contains(vatCell);
                    BigDecimal monthly = null;
                    if (!monthlyRentStr.isEmpty()) {
                        try { monthly = new BigDecimal(monthlyRentStr); } catch (NumberFormatException ignored) { }
                    }
                    leaseIndex.put(key, new LeaseRowSummary(method, totalRent, startDate, endDate, monthly,
                            PortfolioImportPersistService.monthsInclusive(startDate, endDate), rentVat));
                }
            }
        }
    }

    private static BigDecimal computeTotalRentOrNull(String rentAmount, String monthlyRent,
                                                     LocalDate startDate, LocalDate endDate) {
        try {
            if (!monthlyRent.isEmpty()) {
                // The persist phase's rule, so the Cheques-sheet total is checked
                // against the very figure the lease will carry.
                return PortfolioImportPersistService.rentFromMonthly(new BigDecimal(monthlyRent),
                        PortfolioImportPersistService.monthsInclusive(startDate, endDate));
            }
            if (!rentAmount.isEmpty()) {
                return new BigDecimal(rentAmount);
            }
        } catch (NumberFormatException ignored) {
            // Fall through; row already flagged as numeric-error.
        }
        return null;
    }

    static String leaseKey(String propertyName, String unitNumber, String renterEmail) {
        return propertyName.trim().toLowerCase(Locale.ROOT)
                + "|" + unitNumber.trim().toLowerCase(Locale.ROOT)
                + "|" + renterEmail.trim().toLowerCase(Locale.ROOT);
    }

    private static final Set<String> VALID_BOOLS = Set.of("true", "false", "yes", "no", "1", "0");
    private static final List<String> VAT_TOGGLE_HEADERS = List.of(
            "RentVatApplicable", "AdminFeeVatApplicable",
            "SecurityDepositVatApplicable", "ParkingRemoteVatApplicable");

    /** Derived from the {@link PaymentMethod} enum so this allow-list cannot drift if the enum changes. */
    private static final Set<String> VALID_PAYMENT_METHODS = Arrays.stream(PaymentMethod.values())
            .map(Enum::name).collect(Collectors.toUnmodifiableSet());

    private void validateChequesSheet(Sheet sheet,
                                       Map<String, LeaseRowSummary> leaseIndex,
                                       List<ImportErrorDTO> errors,
                                       List<ImportErrorDTO> warnings) {
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);

        // Per-lease state: installments seen (for dup detection) and running sum (for total check).
        Map<String, Set<Integer>> seenInstallments = new HashMap<>();
        Map<String, BigDecimal> sumByLease = new HashMap<>();
        Set<String> chequedLeases = new LinkedHashSet<>();

        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = r + 1;

            String pname = cell(row, hi, "PropertyName");
            String unum = cell(row, hi, "UnitNumber");
            String email = cell(row, hi, "RenterEmail");
            String key = leaseKey(pname, unum, email);
            LeaseRowSummary lease = leaseIndex.get(key);
            if (lease == null) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "PropertyName",
                        "No Leases row matches " + pname + " / " + unum + " / " + email));
                continue;
            }
            chequedLeases.add(key);

            // InstallmentNo: positive int, unique per lease.
            String inoStr = cell(row, hi, "InstallmentNo");
            int ino;
            try {
                ino = Integer.parseInt(inoStr);
                if (ino < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "InstallmentNo",
                        "InstallmentNo must be a positive integer"));
                continue;
            }
            Set<Integer> seen = seenInstallments.computeIfAbsent(key, k -> new HashSet<>());
            if (!seen.add(ino)) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "InstallmentNo",
                        "Duplicate InstallmentNo " + ino + " for this lease"));
            }

            // Method (defaults to lease's PaymentMethod).
            String method = cell(row, hi, "Method").toUpperCase();
            if (method.isEmpty()) method = lease.paymentMethod();
            if (!VALID_PAYMENT_METHODS.contains(method)) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Method",
                        "Method must be one of " + VALID_PAYMENT_METHODS));
                continue;
            }

            // Method-driven required fields.
            String uniqueId = cell(row, hi, "UniqueId");
            String bank = cell(row, hi, "Bank");
            String chequeOrPaymentDate = cell(row, hi, "ChequeOrPaymentDate");
            String dueDate = cell(row, hi, "DueDate");
            if (dueDate.isEmpty()) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "DueDate", "DueDate is required"));
            }
            switch (method) {
                case "CHEQUE":
                    if (uniqueId.isEmpty() || chequeOrPaymentDate.isEmpty() || bank.isEmpty()) {
                        errors.add(new ImportErrorDTO("Cheques", rowNum, "UniqueId",
                                "CHEQUE rows require UniqueId, ChequeOrPaymentDate, and Bank"));
                    }
                    break;
                case "BANK_TRANSFER":
                case "ONLINE":
                    if (bank.isEmpty() || chequeOrPaymentDate.isEmpty()) {
                        errors.add(new ImportErrorDTO("Cheques", rowNum, "Bank",
                                method + " rows require Bank and ChequeOrPaymentDate"));
                    }
                    break;
                case "CASH":
                    // amount + due date only; nothing else required.
                    break;
            }

            // Amount.
            String amtStr = cell(row, hi, "Amount");
            BigDecimal amt = null;
            if (amtStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Amount is required"));
            } else {
                try {
                    amt = new BigDecimal(amtStr);
                    if (amt.signum() < 0) {
                        errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Amount cannot be negative"));
                        amt = null;
                    }
                } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount", "Amount must be a number"));
                }
            }
            if (amt != null) {
                sumByLease.merge(key, amt, BigDecimal::add);
            }

            // DueDate parse + outside-lease window warning.
            if (!dueDate.isEmpty()) {
                LocalDate dd = parseDate(dueDate);
                if (dd == null) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "DueDate", "DueDate: " + DATE_HELP));
                } else if (dd.isBefore(lease.startDate()) || dd.isAfter(lease.endDate())) {
                    warnings.add(new ImportErrorDTO("Cheques", rowNum, "DueDate",
                            "DueDate " + dd + " is outside lease period "
                                    + lease.startDate() + ".." + lease.endDate()));
                }
            }
            // Read by the persist phase as the date on the instrument; a value it
            // cannot read used to be dropped silently in favour of DueDate.
            if (!chequeOrPaymentDate.isEmpty() && parseDate(chequeOrPaymentDate) == null) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "ChequeOrPaymentDate",
                        "ChequeOrPaymentDate: " + DATE_HELP));
            }
        }

        // Sum-vs-rent check, once per lease that had cheque rows. Exact, because the
        // post is exact (Σ cheques = contract value to the fils): a tolerance here
        // showed a clean validation for a row the post then left as a draft
        // (PR #344 review I3). The sheet states what the renter pays, so it is
        // compared with the rent INCLUDING VAT when the rent carries VAT (review
        // I1), and the rent the lease gets is then that sum (review I2 — see
        // PortfolioImportPersistService.contractRent).
        for (String key : chequedLeases) {
            BigDecimal sum = sumByLease.getOrDefault(key, BigDecimal.ZERO);
            String problem = sheetTotalProblem(sum, leaseIndex.get(key));
            if (problem != null) {
                errors.add(ImportErrorDTO.file("Cheques", "Amount", problem + " for " + key));
            }
        }
    }

    /**
     * Whether a lease's Cheques-sheet rows add up to its rent, and if not, why.
     *
     * <ul>
     *   <li>RentAmount: Σ sheet = RentAmount, plus 5% VAT when the rent carries it —
     *       exactly.</li>
     *   <li>MonthlyRent: the rent is Σ sheet (net of VAT), so Σ sheet has to be a
     *       rent whose monthly share rounds to the typed figure — 12 × 8,166.67 =
     *       98,000.04 is, and so is 98,000.00. The same test as
     *       {@link PortfolioImportPersistService#rentFromMonthly}.</li>
     * </ul>
     *
     * @return the refusal, or null when the sheet matches.
     */
    static String sheetTotalProblem(BigDecimal sum, LeaseRowSummary lease) {
        String incl = lease.rentVat() ? " incl. 5% VAT on rent" : "";
        BigDecimal net = PortfolioImportPersistService.rentFromSheet(sum, lease.rentVat());
        if (lease.monthlyRent() != null && lease.months() > 0) {
            BigDecimal expected = gross(lease.totalRent(), lease.rentVat());
            if (net == null || net.divide(BigDecimal.valueOf(lease.months()), 2, RoundingMode.HALF_UP)
                    .compareTo(lease.monthlyRent()) != 0) {
                return "Sum of cheques (" + sum + ") does not match lease total rent (" + expected + incl
                        + ": MonthlyRent " + lease.monthlyRent() + " × " + lease.months() + " months)";
            }
            return null;
        }
        BigDecimal expected = gross(lease.totalRent(), lease.rentVat());
        if (sum.compareTo(expected) != 0) {
            return "Sum of cheques (" + sum + ") does not match lease total rent (" + expected + incl + ")";
        }
        return null;
    }

    private static BigDecimal gross(BigDecimal rent, boolean vat) {
        return rent.add(LeaseVat.vatOfNet(rent, vat, ChargeBehaviour.RENT));
    }

    private void validateDbConflicts(Set<String> propertyNames, Set<String> renterEmails, List<ImportErrorDTO> errors) {
        // Tenant-scoped existence checks. Previously these used
        // findByNameEnIn / findByEmailIn which rely on the Hibernate
        // tenantFilter being enabled by TenantAspect — fragile under
        // @Async where AOP + ThreadLocal propagation isn't guaranteed and
        // a missing filter would leak cross-tenant existence to users.
        // The explicit findByTenantIdAndXxxIn variants are tenant-scoped
        // at the SQL level and stay correct regardless of filter state.
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            // Should never happen — the async dispatcher sets the context
            // before this method runs. Fail loudly rather than silently
            // running an unscoped query.
            throw new IllegalStateException(
                    "validateDbConflicts called without a tenant context; refusing to run unscoped queries");
        }

        // Check existing properties by name, scoped to THIS tenant only.
        List<Property> existingProperties = propertyRepository.findByTenantIdAndNameEnIn(tenantId, propertyNames);
        Set<String> existingPropertyNames = existingProperties.stream()
                .map(Property::getNameEn)
                .collect(Collectors.toSet());

        for (String name : propertyNames) {
            if (existingPropertyNames.contains(name)) {
                // Wording updated: "in this tenant" is accurate; the old
                // "in the system" was misleading regardless of which path
                // produced it.
                errors.add(ImportErrorDTO.file("Properties", "PropertyName", "Property '" + name + "' already exists in this tenant"));
            }
        }

        // Check existing renters by email, scoped to THIS tenant.
        List<Renter> existingRenters = renterRepository.findByTenantIdAndEmailIn(tenantId, renterEmails);
        Set<String> existingEmails = existingRenters.stream()
                .filter(r -> r.getEmail() != null)
                .map(r -> r.getEmail().toLowerCase())
                .collect(Collectors.toSet());

        for (String email : renterEmails) {
            if (existingEmails.contains(email.toLowerCase())) {
                errors.add(ImportErrorDTO.file("Renters", "Email", "Renter with email '" + email + "' already exists in this tenant"));
            }
        }
    }

    // --- Async Orchestrator ---

    @Async("importExecutor")
    public void processImportAsync(byte[] fileBytes, ImportJob job, UUID tenantId) {
        process(fileBytes, job, tenantId, null);
    }

    /**
     * The same, carrying the uploader's {@code Authentication} for the post phase
     * (gap #83): a v1 row the sheet marks ACTIVE is posted after the persist phase
     * has committed, and posting goes through {@code LeaseAccessPolicy}, which fails
     * closed on an executor thread with no user. Without it every such row stays
     * DRAFT and says why. The cut-over's own post job carries its caller the same way.
     */
    @Async("importExecutor")
    public void processImportAsync(byte[] fileBytes, ImportJob job, UUID tenantId, Authentication auth) {
        process(fileBytes, job, tenantId, auth);
    }

    private void process(byte[] fileBytes, ImportJob job, UUID tenantId, Authentication auth) {
        // Set tenant context for this async thread
        TenantContextHolder.setTenantId(tenantId);
        // Through WorkbookGuard, never `new XSSFWorkbook` directly: an uploaded
        // spreadsheet is an untrusted file parsed in-process, and an OOM here would
        // take the whole service down rather than fail the job. See that class for
        // each limit and why it is set where it is.
        try (Workbook workbook = WorkbookGuard.open(fileBytes)) {

            // Phase 1: Validate
            job.setStatus("VALIDATING");
            importJobRepository.save(job);

            ValidationOutcome outcome = validateAll(workbook);
            if (!outcome.errors().isEmpty()) {
                job.setStatus("VALIDATION_FAILED");
                job.setErrors(objectMapper.writeValueAsString(outcome.errors()));
                job.setCompletedAt(Instant.now());
                importJobRepository.save(job);
                return;
            }

            // Phase 2: Persist. Warnings (if any) are folded into the JSONB wrapper
            // alongside the new counters by the persist service.
            job.setStatus("PERSISTING");
            importJobRepository.save(job);

            if (ContractImportValidator.isV2Workbook(workbook)) {
                contractPersistService.persist(workbook, job, outcome.warnings());
            } else {
                PortfolioImportPersistService.PersistResult persisted =
                        persistService.persistWorkbook(workbook, job, outcome.warnings());
                // Only now, with the leases committed: each ACTIVE row posts in a
                // transaction of its own, and a new transaction cannot see rows the
                // persist transaction had not yet committed.
                postRequestedLeases(persisted, job, auth);
            }

            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);

            log.info("Portfolio import completed: jobId={}, properties={}, units={}, leases={}, schedules={}",
                    job.getId(), job.getPropertiesCreated(), job.getUnitsCreated(),
                    job.getLeasesCreated(), job.getSchedulesCreated());
        } catch (BusinessRuleViolationException e) {
            // A workbook this import will not accept at all — not an .xlsx, macro
            // enabled, password protected, or past a size limit. It is a statement
            // about the FILE, so it is reported the way every other statement about
            // the file is: a validation failure the screen already knows how to
            // show, rather than a FAILED job with a stack trace behind it.
            log.warn("Portfolio import refused: jobId={}, reason={}", job.getId(), e.getMessage());
            job.setStatus("VALIDATION_FAILED");
            try {
                job.setErrors(objectMapper.writeValueAsString(
                        List.of(ImportErrorDTO.file("General", "File", e.getMessage()))));
            } catch (Exception jsonEx) {
                job.setErrors("[{\"sheet\":\"General\",\"row\":null,\"field\":\"File\","
                        + "\"message\":\"This workbook was refused\"}]");
            }
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);
        } catch (Exception e) {
            log.error("Portfolio import failed: jobId={}", job.getId(), e);
            job.setStatus("FAILED");
            // Reset counts — the @Transactional on persistWorkbook rolled back all DB writes,
            // so any counts mutated before the exception must not appear on the failed job record
            job.setPropertiesCreated(0);
            job.setBuildingsCreated(0);
            job.setUnitsCreated(0);
            job.setRentersCreated(0);
            job.setLeasesCreated(0);
            job.setSchedulesCreated(0);
            // The persist transaction rolled back, so the batch row it created is
            // gone too; a job still pointing at it would send the web to a 404.
            job.setImportBatchId(null);
            try {
                job.setErrors(objectMapper.writeValueAsString(
                        List.of(ImportErrorDTO.file("General", "File", e.getMessage()))));
            } catch (Exception jsonEx) {
                job.setErrors("[{\"sheet\":\"General\",\"row\":0,\"field\":\"\",\"message\":\"Import failed\"}]");
            }
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** Runs the post phase as the uploader, and gives the thread back clean. */
    private void postRequestedLeases(PortfolioImportPersistService.PersistResult persisted, ImportJob job,
                                     Authentication auth) {
        if (persisted.toPost().isEmpty()) return;
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (auth != null) SecurityContextHolder.getContext().setAuthentication(auth);
            persistService.postRequested(persisted, job);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    // --- Helpers ---

    /**
     * Every date column of the v1 workbook (gap #82): ISO, the day-first forms a UAE
     * user types (DD/MM/YYYY, DD-MM-YYYY) and a real Excel date cell, which
     * {@link SheetCells#getCellString} has already rendered ISO. The cut-over import
     * reads dates through the same {@link SheetCells#parseDateOrNull}, so the two
     * importers accept exactly the same cells. Null when it is none of them.
     */
    static LocalDate parseDate(String value) {
        return SheetCells.parseDateOrNull(value);
    }

    static final String DATE_HELP = "Invalid date. Use YYYY-MM-DD or DD/MM/YYYY (or an Excel date cell)";

    // The three readers and the header index now live in SheetCells, so the
    // cut-over validator in core.service.cutover reads the identical cell the
    // identical way. These stay as one-line delegates purely so the ~30 call
    // sites above are untouched by that move.

    private String getCellString(Row row, int col) {
        return SheetCells.getCellString(row, col);
    }

    private boolean isRowEmpty(Row row) {
        return SheetCells.isRowEmpty(row);
    }

    /** Reads a cell by header name. Returns "" when the header is absent. */
    private String cell(Row row, SheetCells.HeaderIndex hi, String header) {
        return SheetCells.cell(row, hi, header);
    }
}
