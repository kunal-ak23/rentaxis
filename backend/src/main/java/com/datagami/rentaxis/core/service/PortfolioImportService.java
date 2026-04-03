package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

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
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final ObjectMapper objectMapper;

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
        Map<String, Set<String>> unitsByProperty = new HashMap<>(); // propertyName -> set of unitNumbers
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
                Set<String> units = unitsByProperty.computeIfAbsent(propertyName, k -> new HashSet<>());
                if (!units.add(unitNumber)) {
                    errors.add(new ImportErrorDTO("Units", rowNum, "UnitNumber", "Duplicate unit number '" + unitNumber + "' in property '" + propertyName + "'"));
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

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String propertyName = getCellString(row, 0);
            String unitNumber = getCellString(row, 1);
            String renterEmail = getCellString(row, 2);
            String startDateStr = getCellString(row, 3);
            String endDateStr = getCellString(row, 4);
            String rentAmountStr = getCellString(row, 5);
            String paymentMethod = getCellString(row, 8);

            // Cross-sheet: property+unit
            if (propertyName.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PropertyName", "Property name is required"));
            } else if (!propertyNames.contains(propertyName)) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PropertyName", "Property '" + propertyName + "' not found in Properties sheet"));
            }

            if (unitNumber.isEmpty()) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "UnitNumber", "Unit number is required"));
            } else if (!propertyName.isEmpty()) {
                Set<String> units = unitsByProperty.getOrDefault(propertyName, Collections.emptySet());
                if (!units.contains(unitNumber)) {
                    errors.add(new ImportErrorDTO("Leases", rowNum, "UnitNumber", "Unit '" + unitNumber + "' not found in property '" + propertyName + "' on Units sheet"));
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

            // Payment method
            if (!paymentMethod.isEmpty() && !validPaymentMethods.contains(paymentMethod.toUpperCase())) {
                errors.add(new ImportErrorDTO("Leases", rowNum, "PaymentMethod", "Invalid payment method: " + paymentMethod + ". Valid: " + validPaymentMethods));
            }
        }
    }

    private void validateDbConflicts(Set<String> propertyNames, Set<String> renterEmails, List<ImportErrorDTO> errors) {
        // Check existing properties by name
        List<Property> existingProperties = propertyRepository.findAll();
        Set<String> existingPropertyNames = existingProperties.stream()
                .map(Property::getNameEn)
                .collect(Collectors.toSet());

        for (String name : propertyNames) {
            if (existingPropertyNames.contains(name)) {
                errors.add(new ImportErrorDTO("Properties", 0, "PropertyName", "Property '" + name + "' already exists in the system"));
            }
        }

        // Check existing renters by email
        List<Renter> existingRenters = renterRepository.findAll();
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
}
