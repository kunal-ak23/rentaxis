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

    public List<ImportErrorDTO> validateWorkbook(Workbook workbook) {
        List<ImportErrorDTO> errors = new ArrayList<>();

        Sheet propertiesSheet = workbook.getSheet("Properties");
        Sheet unitsSheet = workbook.getSheet("Units");
        Sheet rentersSheet = workbook.getSheet("Renters");
        Sheet leasesSheet = workbook.getSheet("Leases");

        if (propertiesSheet == null) errors.add(new ImportErrorDTO("Properties", 0, "", "Sheet 'Properties' is missing"));
        if (unitsSheet == null) errors.add(new ImportErrorDTO("Units", 0, "", "Sheet 'Units' is missing"));
        if (rentersSheet == null) errors.add(new ImportErrorDTO("Renters", 0, "", "Sheet 'Renters' is missing"));
        if (leasesSheet == null) errors.add(new ImportErrorDTO("Leases", 0, "", "Sheet 'Leases' is missing"));

        if (!errors.isEmpty()) return errors;

        // Collect data for cross-sheet validation
        Set<String> propertyNames = new HashSet<>();
        Map<String, Set<String>> unitsByProperty = new HashMap<>(); // propertyName -> set of "buildingName|unitNumber" composite keys
        Set<String> renterEmails = new HashSet<>();

        // Validate Properties sheet
        validatePropertiesSheet(propertiesSheet, errors, propertyNames);

        // Validate Units sheet
        validateUnitsSheet(unitsSheet, errors, propertyNames, unitsByProperty);

        // Validate Renters sheet
        validateRentersSheet(rentersSheet, errors, renterEmails);

        // Validate Leases sheet (cross-sheet refs)
        validateLeasesSheet(leasesSheet, errors, propertyNames, unitsByProperty, renterEmails);

        // Check DB conflicts
        validateDbConflicts(propertyNames, renterEmails, errors);

        return errors;
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
                                     Set<String> renterEmails) {
        Set<String> validPaymentMethods = Arrays.stream(PaymentMethod.values()).map(Enum::name).collect(Collectors.toSet());
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

            // Rent amount
            if (rentAmountStr.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount", "Rent amount is required"));
            } else {
                try { Double.parseDouble(rentAmountStr); } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "RentAmount", "Rent amount must be numeric"));
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
            if (!paymentMethod.isEmpty() && !validPaymentMethods.contains(paymentMethod.toUpperCase())) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PaymentMethod", "Invalid payment method: " + paymentMethod + ". Valid: " + validPaymentMethods));
            }
        }
    }

    private void validateDbConflicts(Set<String> propertyNames, Set<String> renterEmails, List<ImportErrorDTO> errors) {
        // Check existing properties by name
        List<Property> existingProperties = propertyRepository.findByNameEnIn(propertyNames);
        Set<String> existingPropertyNames = existingProperties.stream()
                .map(Property::getNameEn)
                .collect(Collectors.toSet());

        for (String name : propertyNames) {
            if (existingPropertyNames.contains(name)) {
                errors.add(new ImportErrorDTO("Properties", 0, "PropertyName", "Property '" + name + "' already exists in the system"));
            }
        }

        // Check existing renters by email
        List<Renter> existingRenters = renterRepository.findByEmailIn(renterEmails);
        Set<String> existingEmails = existingRenters.stream()
                .filter(r -> r.getEmail() != null)
                .map(r -> r.getEmail().toLowerCase())
                .collect(Collectors.toSet());

        for (String email : renterEmails) {
            if (existingEmails.contains(email.toLowerCase())) {
                errors.add(new ImportErrorDTO("Renters", 0, "Email", "Renter with email '" + email + "' already exists in the system"));
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

            List<ImportErrorDTO> errors = validateWorkbook(workbook);
            if (!errors.isEmpty()) {
                job.setStatus("VALIDATION_FAILED");
                job.setErrors(objectMapper.writeValueAsString(errors));
                job.setCompletedAt(Instant.now());
                importJobRepository.save(job);
                return;
            }

            // Phase 2: Persist
            job.setStatus("PERSISTING");
            importJobRepository.save(job);

            persistService.persistWorkbook(workbook, job);

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
