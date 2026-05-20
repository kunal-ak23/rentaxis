package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
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
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // --- Validation Phase (no DB writes) ---

    /**
     * Full validation pass. Returns hard errors (block import) and warnings (informational only).
     */
    public ValidationOutcome validateAll(Workbook workbook) {
        List<ImportErrorDTO> errors = new ArrayList<>();
        List<ImportErrorDTO> warnings = new ArrayList<>();

        Sheet propertiesSheet = workbook.getSheet("Properties");
        Sheet unitsSheet = workbook.getSheet("Units");
        Sheet rentersSheet = workbook.getSheet("Renters");
        Sheet leasesSheet = workbook.getSheet("Leases");

        if (propertiesSheet == null) errors.add(new ImportErrorDTO("Properties", 0, "", "Sheet 'Properties' is missing"));
        if (unitsSheet == null) errors.add(new ImportErrorDTO("Units", 0, "", "Sheet 'Units' is missing"));
        if (rentersSheet == null) errors.add(new ImportErrorDTO("Renters", 0, "", "Sheet 'Renters' is missing"));
        if (leasesSheet == null) errors.add(new ImportErrorDTO("Leases", 0, "", "Sheet 'Leases' is missing"));

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
        validateLeasesSheet(leasesSheet, errors, propertyNames, unitsByProperty, renterEmails, leaseIndex);

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
                           LocalDate startDate, LocalDate endDate) {}

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
                                     Map<String, LeaseRowSummary> leaseIndex) {
        HeaderIndex hi = new HeaderIndex(sheet);

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
                try { startDate = parseDate(startDateStr); } catch (DateTimeParseException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "StartDate", "Invalid date format. Use YYYY-MM-DD"));
                }
            }
            if (endDateStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "EndDate", "End date is required"));
            } else {
                try { endDate = parseDate(endDateStr); } catch (DateTimeParseException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "EndDate", "Invalid date format. Use YYYY-MM-DD"));
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
                try { LocalDate.parse(agreementDate); }
                catch (DateTimeParseException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "AgreementDate", "AgreementDate must be ISO format (YYYY-MM-DD)"));
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
                try { LocalDate.parse(bdDate); }
                catch (DateTimeParseException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "BookingDeposit_Date",
                            "BookingDeposit_Date must be ISO format (YYYY-MM-DD)"));
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
                    leaseIndex.put(key, new LeaseRowSummary(method, totalRent, startDate, endDate));
                }
            }
        }
    }

    private static BigDecimal computeTotalRentOrNull(String rentAmount, String monthlyRent,
                                                     LocalDate startDate, LocalDate endDate) {
        try {
            if (!monthlyRent.isEmpty()) {
                BigDecimal mr = new BigDecimal(monthlyRent);
                // Mirror PortfolioImportPersistService.monthsInclusive — end date is
                // inclusive in our lease convention so Jan 1 → Dec 31 counts as 12.
                long months = Math.max(ChronoUnit.MONTHS.between(startDate, endDate.plusDays(1)), 1);
                return mr.multiply(BigDecimal.valueOf(months));
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
        HeaderIndex hi = new HeaderIndex(sheet);

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
                try {
                    LocalDate dd = LocalDate.parse(dueDate);
                    if (dd.isBefore(lease.startDate()) || dd.isAfter(lease.endDate())) {
                        warnings.add(new ImportErrorDTO("Cheques", rowNum, "DueDate",
                                "DueDate " + dd + " is outside lease period "
                                        + lease.startDate() + ".." + lease.endDate()));
                    }
                } catch (DateTimeParseException e) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "DueDate",
                            "DueDate must be ISO format (YYYY-MM-DD)"));
                }
            }
        }

        // Sum-vs-totalRent check, evaluated once per lease that had cheque rows.
        BigDecimal tolerance = new BigDecimal("1.00");
        for (String key : chequedLeases) {
            BigDecimal sum = sumByLease.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal totalRent = leaseIndex.get(key).totalRent();
            if (sum.subtract(totalRent).abs().compareTo(tolerance) > 0) {
                errors.add(new ImportErrorDTO("Cheques", 0, "Amount",
                        "Sum of cheques (" + sum + ") does not match lease total rent ("
                                + totalRent + ") for " + key));
            }
        }
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
                errors.add(new ImportErrorDTO("Properties", 0, "PropertyName", "Property '" + name + "' already exists in this tenant"));
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
                errors.add(new ImportErrorDTO("Renters", 0, "Email", "Renter with email '" + email + "' already exists in this tenant"));
            }
        }
    }

    // --- Async Orchestrator ---

    @Async("importExecutor")
    public void processImportAsync(byte[] fileBytes, ImportJob job, UUID tenantId) {
        // Set tenant context for this async thread
        TenantContextHolder.setTenantId(tenantId);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(fileBytes))) {

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

            persistService.persistWorkbook(workbook, job, outcome.warnings());

            job.setStatus("COMPLETED");
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);

            log.info("Portfolio import completed: jobId={}, properties={}, units={}, leases={}, schedules={}",
                    job.getId(), job.getPropertiesCreated(), job.getUnitsCreated(),
                    job.getLeasesCreated(), job.getSchedulesCreated());
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
            try {
                job.setErrors(objectMapper.writeValueAsString(
                        List.of(new ImportErrorDTO("General", 0, "", e.getMessage()))));
            } catch (Exception jsonEx) {
                job.setErrors("[{\"sheet\":\"General\",\"row\":0,\"field\":\"\",\"message\":\"Import failed\"}]");
            }
            job.setCompletedAt(Instant.now());
            importJobRepository.save(job);
        } finally {
            TenantContextHolder.clear();
        }
    }

    // --- Helpers ---

    private LocalDate parseDate(String value) {
        return LocalDate.parse(value.trim());
    }

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    private boolean isRowEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!getCellString(row, i).isEmpty()) return false;
        }
        return true;
    }

    /** Reads a cell by header name. Returns "" when the header is absent. */
    private String cell(Row row, HeaderIndex hi, String header) {
        int c = hi.col(header);
        return c < 0 ? "" : getCellString(row, c);
    }

    /**
     * Maps header names (case-insensitive, trimmed) to column indexes for a sheet.
     * Lets us read columns by name so appending new columns in
     * PortfolioTemplateService doesn't break old workbooks that omit them.
     */
    static final class HeaderIndex {
        private final Map<String, Integer> byName;

        HeaderIndex(Sheet sheet) {
            Map<String, Integer> m = new HashMap<>();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) header = sheet.getRow(0);
            if (header != null) {
                for (int c = 0; c < header.getLastCellNum(); c++) {
                    Cell cell = header.getCell(c);
                    if (cell == null) continue;
                    String v = cell.getCellType() == CellType.STRING
                            ? cell.getStringCellValue().trim()
                            : "";
                    if (!v.isEmpty()) m.put(v.toLowerCase(Locale.ROOT), c);
                }
            }
            this.byName = m;
        }

        /** -1 when the header isn't present (old template). */
        int col(String name) {
            Integer v = byName.get(name.toLowerCase(Locale.ROOT));
            return v == null ? -1 : v;
        }

        boolean has(String name) {
            return byName.containsKey(name.toLowerCase(Locale.ROOT));
        }
    }
}
