package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioImportPersistService {

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final ImportJobRepository importJobRepository;

    @Transactional
    public void persistWorkbook(Workbook workbook, ImportJob job) {
        Sheet propertiesSheet = workbook.getSheet("Properties");
        Sheet unitsSheet = workbook.getSheet("Units");
        Sheet rentersSheet = workbook.getSheet("Renters");
        Sheet leasesSheet = workbook.getSheet("Leases");

        // 1. Create Properties
        Map<String, Property> propertyMap = new LinkedHashMap<>();
        for (int i = 1; i <= propertiesSheet.getLastRowNum(); i++) {
            Row row = propertiesSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            Property p = new Property();
            p.setNameEn(getCellString(row, 0));
            p.setNameAr(getCellString(row, 1).isEmpty() ? null : getCellString(row, 1));
            p.setEmirate(Emirate.valueOf(getCellString(row, 2).toUpperCase()));
            p.setAddress(getCellString(row, 3).isEmpty() ? null : getCellString(row, 3));
            p.setType(PropertyType.valueOf(getCellString(row, 4).toUpperCase()));
            p.setMakaniNumber(getCellString(row, 5).isEmpty() ? null : getCellString(row, 5));

            propertyMap.put(p.getNameEn(), propertyRepository.save(p));
        }
        job.setPropertiesCreated(propertyMap.size());

        // 2. Create Buildings (deduplicate by property + buildingName)
        Map<String, Building> buildingMap = new LinkedHashMap<>(); // "propertyName|buildingName" -> Building
        int buildingsCreated = 0;
        for (int i = 1; i <= unitsSheet.getLastRowNum(); i++) {
            Row row = unitsSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            String propertyName = getCellString(row, 0);
            String buildingName = getCellString(row, 1);
            if (buildingName.isEmpty()) continue;

            String key = propertyName + "|" + buildingName;
            if (!buildingMap.containsKey(key)) {
                Property property = propertyMap.get(propertyName);
                Building b = new Building();
                b.setProperty(property);
                b.setNameEn(buildingName);
                buildingMap.put(key, buildingRepository.save(b));
                buildingsCreated++;
            }
        }
        job.setBuildingsCreated(buildingsCreated);

        // 3. Create Units
        Map<String, Unit> unitMap = new LinkedHashMap<>(); // "propertyName|unitNumber" -> Unit
        for (int i = 1; i <= unitsSheet.getLastRowNum(); i++) {
            Row row = unitsSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            String propertyName = getCellString(row, 0);
            String buildingName = getCellString(row, 1);
            String unitNumber = getCellString(row, 2);
            String unitType = getCellString(row, 3);
            String sizeSqft = getCellString(row, 4);
            String expectedRent = getCellString(row, 5);

            Property property = propertyMap.get(propertyName);
            Unit u = new Unit();
            u.setProperty(property);
            u.setUnitNumber(unitNumber);
            u.setStatus(UnitStatus.VACANT);

            if (!buildingName.isEmpty()) {
                u.setBuilding(buildingMap.get(propertyName + "|" + buildingName));
            }
            if (!unitType.isEmpty()) {
                u.setType(UnitType.valueOf(unitType.toUpperCase()));
            }
            if (!sizeSqft.isEmpty()) {
                u.setSizeSqft(new BigDecimal(sizeSqft));
            }
            if (!expectedRent.isEmpty()) {
                u.setExpectedRent(new BigDecimal(expectedRent));
            }

            unitMap.put(propertyName + "|" + unitNumber, unitRepository.save(u));
        }
        job.setUnitsCreated(unitMap.size());

        // 4. Create Renters
        Map<String, Renter> renterMap = new LinkedHashMap<>(); // email -> Renter
        for (int i = 1; i <= rentersSheet.getLastRowNum(); i++) {
            Row row = rentersSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            Renter r = new Renter();
            r.setNameEn(getCellString(row, 0));
            r.setNameAr(getCellString(row, 1).isEmpty() ? null : getCellString(row, 1));
            r.setEmail(getCellString(row, 2));
            r.setPhone(getCellString(row, 3).isEmpty() ? null : getCellString(row, 3));

            renterMap.put(r.getEmail().toLowerCase(), renterRepository.save(r));
        }
        job.setRentersCreated(renterMap.size());

        // 5. Create Leases + Payment Schedules
        int leasesCreated = 0;
        int schedulesCreated = 0;
        for (int i = 1; i <= leasesSheet.getLastRowNum(); i++) {
            Row row = leasesSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            String propertyName = getCellString(row, 0);
            String unitNumber = getCellString(row, 1);
            String renterEmail = getCellString(row, 2);
            LocalDate startDate = parseDate(getCellString(row, 3));
            LocalDate endDate = parseDate(getCellString(row, 4));
            BigDecimal rentAmount = new BigDecimal(getCellString(row, 5));
            String depositStr = getCellString(row, 6);
            String paymentTermsStr = getCellString(row, 7);
            String paymentMethodStr = getCellString(row, 8);
            String ejariNumber = getCellString(row, 9);

            Unit unit = unitMap.get(propertyName + "|" + unitNumber);
            Renter renter = renterMap.get(renterEmail.toLowerCase());

            Lease lease = new Lease();
            lease.setUnit(unit);
            lease.setRenter(renter);
            lease.setStartDate(startDate);
            lease.setEndDate(endDate);
            lease.setRentAmount(rentAmount);
            lease.setStatus(LeaseStatus.ACTIVE);

            if (!depositStr.isEmpty()) {
                lease.setDepositAmount(new BigDecimal(depositStr));
            }

            int installments = 12;
            if (!paymentTermsStr.isEmpty()) {
                installments = Integer.parseInt(paymentTermsStr);
                lease.setPaymentTerms(installments);
            }

            // Calculate monthly rent using installments (payment terms) to avoid
            // ChronoUnit.MONTHS off-by-one (e.g. Jan 1 - Dec 31 = 11 months, not 12)
            lease.setMonthlyRent(rentAmount.divide(BigDecimal.valueOf(installments), 2, RoundingMode.HALF_UP));
            if (!paymentMethodStr.isEmpty()) {
                lease.setPaymentMethod(PaymentMethod.valueOf(paymentMethodStr.toUpperCase()));
            }
            if (!ejariNumber.isEmpty()) {
                lease.setEjariNumber(ejariNumber);
            }

            Lease savedLease = leaseRepository.save(lease);
            leasesCreated++;

            // Update unit status to OCCUPIED
            unit.setStatus(UnitStatus.OCCUPIED);
            unit.setCurrentTenantName(renter.getNameEn());
            unit.setActualRent(rentAmount);
            unitRepository.save(unit);

            // Auto-generate payment schedules
            var schedules = paymentScheduleService.generateScheduleForLease(savedLease);
            schedulesCreated += schedules.size();
        }

        job.setLeasesCreated(leasesCreated);
        job.setSchedulesCreated(schedulesCreated);
        importJobRepository.save(job);
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
