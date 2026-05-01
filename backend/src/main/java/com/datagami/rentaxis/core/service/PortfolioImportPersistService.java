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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
    private final PaymentScheduleRepository paymentScheduleRepository;
    private final ImportJobRepository importJobRepository;

    private static final Set<String> VALID_BOOLS_TRUE = Set.of("true", "yes", "1");
    private static final Set<String> VALID_BOOLS_FALSE = Set.of("false", "no", "0");

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
            String emirateStr = getCellString(row, 2).trim().toUpperCase().replace(" ", "_");
            log.info("Parsing emirate from cell: '{}' -> '{}'", getCellString(row, 2), emirateStr);
            p.setEmirate(Emirate.valueOf(emirateStr));
            p.setAddress(getCellString(row, 3).isEmpty() ? null : getCellString(row, 3));
            p.setType(PropertyType.valueOf(getCellString(row, 4).trim().toUpperCase().replace(" ", "_")));
            p.setMakaniNumber(getCellString(row, 5).isEmpty() ? null : getCellString(row, 5));

            propertyMap.put(p.getNameEn().toLowerCase(), propertyRepository.save(p));
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

            String key = propertyName.toLowerCase() + "|" + buildingName.toLowerCase();
            if (!buildingMap.containsKey(key)) {
                Property property = propertyMap.get(propertyName.toLowerCase());
                Building b = new Building();
                b.setProperty(property);
                b.setNameEn(buildingName);
                buildingMap.put(key, buildingRepository.save(b));
                buildingsCreated++;
            }
        }
        job.setBuildingsCreated(buildingsCreated);

        // 3. Create Units
        Map<String, Unit> unitMap = new LinkedHashMap<>(); // "propertyName|buildingName|unitNumber" -> Unit
        for (int i = 1; i <= unitsSheet.getLastRowNum(); i++) {
            Row row = unitsSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            String propertyName = getCellString(row, 0);
            String buildingName = getCellString(row, 1);
            String unitNumber = getCellString(row, 2);
            String unitType = getCellString(row, 3);
            String sizeSqft = getCellString(row, 4);
            String expectedRent = getCellString(row, 5);

            Property property = propertyMap.get(propertyName.toLowerCase());
            Unit u = new Unit();
            u.setProperty(property);
            u.setUnitNumber(unitNumber);
            u.setStatus(UnitStatus.VACANT);

            if (!buildingName.isEmpty()) {
                u.setBuilding(buildingMap.get(propertyName.toLowerCase() + "|" + buildingName.toLowerCase()));
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

            unitMap.put(propertyName.toLowerCase() + "|" + buildingName.toLowerCase() + "|" + unitNumber.toLowerCase(), unitRepository.save(u));
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
        HeaderIndex leaseHi = new HeaderIndex(leasesSheet);
        int leasesCreated = 0;
        int schedulesCreated = 0;
        for (int i = 1; i <= leasesSheet.getLastRowNum(); i++) {
            Row row = leasesSheet.getRow(i);
            if (row == null || isRowEmpty(row)) continue;

            String propertyName = cell(row, leaseHi, "PropertyName");
            String buildingName = cell(row, leaseHi, "BuildingName");
            String unitNumber = cell(row, leaseHi, "UnitNumber");
            String renterEmail = cell(row, leaseHi, "RenterEmail");
            LocalDate startDate = parseDate(cell(row, leaseHi, "StartDate"));
            LocalDate endDate = parseDate(cell(row, leaseHi, "EndDate"));
            String rentAmountStr = cell(row, leaseHi, "RentAmount");
            String monthlyRentStr = cell(row, leaseHi, "MonthlyRent");
            String depositStr = cell(row, leaseHi, "DepositAmount");
            String paymentTermsStr = cell(row, leaseHi, "PaymentTerms");
            String paymentMethodStr = cell(row, leaseHi, "PaymentMethod");
            String ejariNumber = cell(row, leaseHi, "EjariNumber");

            Unit unit = unitMap.get(propertyName.toLowerCase() + "|" + buildingName.toLowerCase() + "|" + unitNumber.toLowerCase());
            Renter renter = renterMap.get(renterEmail.toLowerCase());

            if (unit == null) {
                throw new IllegalStateException(
                        "Unit not found during persist for: " + propertyName + " | " + buildingName + " | " + unitNumber +
                        " — this indicates a validation bug, please report it");
            }
            if (renter == null) {
                throw new IllegalStateException(
                        "Renter not found during persist for email: " + renterEmail +
                        " — this indicates a validation bug, please report it");
            }

            // Compute monthlyRent + totalRent. Fixes the prior bug where monthlyRent
            // was computed as totalRent / paymentTerms — that gave per-installment
            // amount, not per-month rent, whenever paymentTerms != monthsBetween.
            long monthsBetween = Math.max(ChronoUnit.MONTHS.between(startDate, endDate), 1);
            BigDecimal monthlyRent;
            BigDecimal totalRent;
            if (!monthlyRentStr.isEmpty()) {
                monthlyRent = new BigDecimal(monthlyRentStr);
                totalRent = monthlyRent.multiply(BigDecimal.valueOf(monthsBetween));
            } else {
                totalRent = new BigDecimal(rentAmountStr);
                monthlyRent = totalRent.divide(BigDecimal.valueOf(monthsBetween), 2, RoundingMode.HALF_UP);
            }

            Lease lease = new Lease();
            lease.setUnit(unit);
            lease.setRenter(renter);
            lease.setStartDate(startDate);
            lease.setEndDate(endDate);
            lease.setRentAmount(totalRent);
            lease.setMonthlyRent(monthlyRent);

            if (!depositStr.isEmpty()) {
                lease.setDepositAmount(new BigDecimal(depositStr));
            }
            if (!paymentTermsStr.isEmpty()) {
                lease.setPaymentTerms(Integer.parseInt(paymentTermsStr));
            }
            if (!paymentMethodStr.isEmpty()) {
                lease.setPaymentMethod(PaymentMethod.valueOf(paymentMethodStr.toUpperCase()));
            }
            if (!ejariNumber.isEmpty()) {
                lease.setEjariNumber(ejariNumber);
            }

            // ---- New lease-agreement fields (added 2026-05-02) ----
            BigDecimal adminFee = parseDecimalOrZero(cell(row, leaseHi, "AdminFee"));
            BigDecimal parkingRemoteFee = parseDecimalOrZero(cell(row, leaseHi, "ParkingRemoteFee"));
            lease.setAdminFee(adminFee);
            lease.setParkingRemoteFee(parkingRemoteFee);

            boolean commercialDefault = unit.getProperty().getType() == PropertyType.COMMERCIAL;
            lease.setRentVatApplicable(parseBoolOrDefault(cell(row, leaseHi, "RentVatApplicable"), commercialDefault));
            lease.setAdminFeeVatApplicable(parseBoolOrDefault(cell(row, leaseHi, "AdminFeeVatApplicable"), commercialDefault));
            lease.setSecurityDepositVatApplicable(parseBoolOrDefault(cell(row, leaseHi, "SecurityDepositVatApplicable"), commercialDefault));
            lease.setParkingRemoteVatApplicable(parseBoolOrDefault(cell(row, leaseHi, "ParkingRemoteVatApplicable"), commercialDefault));

            String depMethod = cell(row, leaseHi, "DepositPaymentMethod").toUpperCase();
            if (!depMethod.isEmpty()) {
                lease.setDepositPaymentMethod(PaymentMethod.valueOf(depMethod));
            }

            String agreementStr = cell(row, leaseHi, "AgreementDate");
            if (!agreementStr.isEmpty()) {
                try { lease.setAgreementDate(LocalDate.parse(agreementStr)); }
                catch (DateTimeParseException ignored) { /* validator already flagged this */ }
            }

            String statusStr = cell(row, leaseHi, "Status").toUpperCase();
            LeaseStatus leaseStatus = "DRAFT".equals(statusStr) ? LeaseStatus.DRAFT : LeaseStatus.ACTIVE;
            lease.setStatus(leaseStatus);

            Lease savedLease = leaseRepository.save(lease);
            leasesCreated++;

            // Booking deposit: persist a PaymentSchedule row mirroring LeaseService.createDraftLease.
            String bdAmt = cell(row, leaseHi, "BookingDeposit_Amount");
            if (!bdAmt.isEmpty()) {
                PaymentSchedule booking = new PaymentSchedule();
                booking.setLease(savedLease);
                booking.setUnit(unit);
                booking.setProperty(unit.getProperty());
                booking.setInstallmentNumber(0);
                booking.setAmount(new BigDecimal(bdAmt));
                String bdNum = cell(row, leaseHi, "BookingDeposit_Number");
                String bdBank = cell(row, leaseHi, "BookingDeposit_Bank");
                LocalDate bdDate = parseDate(cell(row, leaseHi, "BookingDeposit_Date"));
                booking.setChequeNumber(bdNum.isEmpty() ? null : bdNum);
                booking.setBankName(bdBank.isEmpty() ? null : bdBank);
                booking.setChequeDate(bdDate);
                booking.setDueDate(bdDate);
                booking.setStatus(PaymentStatus.PENDING);
                booking.setPaymentMethod(savedLease.getPaymentMethod() != null
                        ? savedLease.getPaymentMethod().name() : "CHEQUE");
                booking.setPurposeLabel("BOOKING RECEIVED");
                booking.setBookingDeposit(true);
                paymentScheduleRepository.save(booking);
            }

            // Apply lease-status-driven side effects.
            if (leaseStatus == LeaseStatus.ACTIVE) {
                unit.setStatus(UnitStatus.OCCUPIED);
                unit.setCurrentTenantName(renter.getNameEn());
                unit.setActualRent(totalRent);
                unitRepository.save(unit);
            }
            // DRAFT → unit stays VACANT, no further change.

            // Auto-generate payment schedules
            var schedules = paymentScheduleService.generateScheduleForLease(savedLease);
            schedulesCreated += schedules.size();
        }

        job.setLeasesCreated(leasesCreated);
        job.setSchedulesCreated(schedulesCreated);
        importJobRepository.save(job);
    }

    private static BigDecimal parseDecimalOrZero(String s) {
        if (s == null || s.isEmpty()) return BigDecimal.ZERO;
        try { return new BigDecimal(s); }
        catch (NumberFormatException e) { return BigDecimal.ZERO; }
    }

    private static boolean parseBoolOrDefault(String s, boolean fallback) {
        if (s == null) return fallback;
        String v = s.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return fallback;
        if (VALID_BOOLS_TRUE.contains(v)) return true;
        if (VALID_BOOLS_FALSE.contains(v)) return false;
        return fallback;
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
     * Mirrors {@code PortfolioImportService.HeaderIndex} — kept duplicated rather than
     * extracted because the two services are independent and the helper is small.
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
