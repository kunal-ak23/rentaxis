package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.util.DateMath;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    private final ImportJobRepository importJobRepository;
    private final LeaseService leaseService;
    private final ChargeTypeService chargeTypeService;

    private static final Set<String> VALID_BOOLS_TRUE = Set.of("true", "yes", "1");
    private static final Set<String> VALID_BOOLS_FALSE = Set.of("false", "no", "0");

    @Transactional
    public void persistWorkbook(Workbook workbook, ImportJob job) {
        persistWorkbook(workbook, job, List.of());
    }

    /**
     * Same as {@link #persistWorkbook(Workbook, ImportJob)} but additionally folds the
     * supplied validation warnings into the {@link PortfolioImportJobDetailsDTO}
     * wrapper persisted in {@code job.errors}, so the controller can surface them.
     */
    @Transactional
    public void persistWorkbook(Workbook workbook, ImportJob job, List<ImportErrorDTO> warnings) {
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

        // 5. Pre-read the optional Cheques sheet, keyed by lease.
        Map<String, List<ChequeRow>> chequesByLeaseKey = readChequesSheet(workbook);

        // 6. Create Leases + their charge lines.
        //
        // The lines are built by code ("RENT", "SECURITY_DEPOSIT", ...), so the
        // tenant's catalogue has to exist before the loop. Seeding is idempotent
        // and leaves any row the tenant has already edited alone — importing into
        // a brand-new tenant that has not opened the accounting screens yet would
        // otherwise fail on the first lease with "unknown charge type RENT".
        chargeTypeService.seedDefaults();

        HeaderIndex leaseHi = new HeaderIndex(leasesSheet);
        int leasesCreated = 0;
        int chequesFromSheet = 0;
        int bookingDepositsCreated = 0;
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
            //
            // End-date is inclusive in the UAE lease convention (Jan 1 → Dec 31 is a
            // 12-month lease), so we step end forward by one day before counting
            // whole months. Without this, MONTHS.between(2026-01-01, 2026-12-31)
            // returns 11 and admins entering MonthlyRent=5000 would get
            // totalRent=55,000 instead of the expected 60,000. The Cheques-sheet sum
            // check (PortfolioImportService.computeTotalRentOrNull) uses the same
            // helper so cheque totals validate against the same number.
            long monthsBetween = monthsInclusive(startDate, endDate);
            BigDecimal totalRent = monthlyRentStr.isEmpty()
                    ? new BigDecimal(rentAmountStr)
                    : new BigDecimal(monthlyRentStr).multiply(BigDecimal.valueOf(monthsBetween));

            Lease lease = new Lease();
            lease.setUnit(unit);
            lease.setRenter(renter);
            lease.setStartDate(startDate);
            lease.setEndDate(endDate);
            // rentAmount / depositAmount are derived from the lines below; they are
            // set here only so the not-null columns have a value before the first
            // insert. syncDerivedTotals overwrites both.
            lease.setRentAmount(totalRent);

            BigDecimal deposit = depositStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(depositStr);
            lease.setDepositAmount(deposit);
            if (!paymentTermsStr.isEmpty()) {
                lease.setPaymentTerms(Integer.parseInt(paymentTermsStr));
            }
            if (!paymentMethodStr.isEmpty()) {
                lease.setPaymentMethod(PaymentMethod.valueOf(paymentMethodStr.toUpperCase()));
            }
            if (!ejariNumber.isEmpty()) {
                lease.setEjariNumber(ejariNumber);
            }

            // ---- Lease-agreement fields ----
            // AdminFee / ParkingRemoteFee columns now map to flexible LeaseCharge
            // ONE_TIME rows (created after the lease is saved). Their VAT intent
            // comes from the matching VAT columns if present, else the
            // commercial default.
            BigDecimal adminFee = parseDecimalOrZero(cell(row, leaseHi, "AdminFee"));
            BigDecimal parkingRemoteFee = parseDecimalOrZero(cell(row, leaseHi, "ParkingRemoteFee"));

            boolean commercialDefault = unit.getProperty().getType() == PropertyType.COMMERCIAL;
            lease.setRentVatApplicable(parseBoolOrDefault(cell(row, leaseHi, "RentVatApplicable"), commercialDefault));
            boolean adminFeeVat = parseBoolOrDefault(cell(row, leaseHi, "AdminFeeVatApplicable"), commercialDefault);
            boolean parkingRemoteVat = parseBoolOrDefault(cell(row, leaseHi, "ParkingRemoteVatApplicable"), commercialDefault);

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

            // Contract header. contract_date is what the posting journal will be
            // filed under, so an imported lease needs one even though the sheet
            // has no column for it.
            lease.setContractDate(lease.getAgreementDate() != null ? lease.getAgreementDate() : startDate);
            lease.setFirstDueDate(startDate);

            Lease savedLease = leaseRepository.save(lease);
            if (savedLease.getChainId() == null) {
                savedLease.setChainId(savedLease.getId());
                savedLease = leaseRepository.save(savedLease);
            }
            leasesCreated++;

            // The sheet's money columns become charge lines. This is the same
            // entry point the draft wizard uses, so an imported lease posts
            // through exactly the same rules — the previous pair of
            // LeaseCharge rows plus hand-built PaymentSchedule rows was a second
            // representation of the same amounts that only the import wrote.
            List<LeaseLineInput> lines = new ArrayList<>();
            lines.add(line("RENT", totalRent, lease.isRentVatApplicable(), startDate, endDate));
            if (deposit.signum() > 0) {
                lines.add(line("SECURITY_DEPOSIT", deposit, false, null, null));
            }
            if (adminFee.signum() > 0) {
                lines.add(line("ADMIN_FEE", adminFee, adminFeeVat, null, null));
            }
            if (parkingRemoteFee.signum() > 0) {
                lines.add(line("PARKING_FEE", parkingRemoteFee, parkingRemoteVat, null, null));
            }
            leaseService.applyLines(savedLease, lines);
            leaseService.syncDerivedTotals(savedLease);
            savedLease = leaseRepository.save(savedLease);

            // The Leases-sheet validator enforces all-or-nothing across the four
            // BookingDeposit_* columns, so reaching this branch means Date /
            // Number / Bank are also non-empty and parseable. We re-assert here to
            // fail fast if a caller skipped validation.
            String bdAmt = cell(row, leaseHi, "BookingDeposit_Amount");
            if (!bdAmt.isEmpty()) {
                String bdNum = cell(row, leaseHi, "BookingDeposit_Number");
                String bdBank = cell(row, leaseHi, "BookingDeposit_Bank");
                String bdDateStr = cell(row, leaseHi, "BookingDeposit_Date");
                if (bdDateStr.isEmpty() || bdNum.isEmpty() || bdBank.isEmpty()) {
                    throw new IllegalStateException(
                            "BookingDeposit_Amount set without all of Date/Number/Bank on row " + (i + 1) +
                            " — this indicates a validation bug, please report it");
                }
                bookingDepositsCreated++;
            }

            // Apply lease-status-driven side effects.
            if (leaseStatus == LeaseStatus.ACTIVE) {
                unit.setStatus(UnitStatus.OCCUPIED);
                unit.setCurrentTenantName(renter.getNameEn());
                unit.setActualRent(savedLease.getRentAmount());
                unitRepository.save(unit);
            }
            // DRAFT → unit stays VACANT, no further change.

            // The import no longer builds a payment plan. Instalments are cheques
            // now, generated against the lease's lines (Task 5); a Cheques sheet
            // still fixes how many instalments the lease has, which is the one
            // piece of that sheet the generator needs.
            String chequesKey = leaseKey(propertyName, unitNumber, renterEmail);
            List<ChequeRow> chequeRows = chequesByLeaseKey.getOrDefault(chequesKey, List.of());
            if (!chequeRows.isEmpty()) {
                savedLease.setPaymentTerms(chequeRows.size());
                leaseRepository.save(savedLease);
                chequesFromSheet += chequeRows.size();
            }
        }

        job.setLeasesCreated(leasesCreated);
        // The import creates no payment schedules any more (cheques are generated
        // from the lease's lines in Task 5). The counter stays on the job so the
        // column and the UI that reads it do not have to change mid-plan.
        job.setSchedulesCreated(0);

        // Persist the new counters and any warnings via the existing JSONB `errors`
        // column using the PortfolioImportJobDetailsDTO wrapper. Avoids a DB migration;
        // the controller reads either the legacy array form (validation-failed jobs)
        // or this wrapper. Wrapper is written when there's anything to surface
        // beyond the legacy fields.
        boolean hasWarnings = warnings != null && !warnings.isEmpty();
        if (chequesFromSheet > 0 || bookingDepositsCreated > 0 || hasWarnings) {
            try {
                PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
                if (chequesFromSheet > 0) details.setChequesFromSheet(chequesFromSheet);
                if (bookingDepositsCreated > 0) details.setBookingDepositsCreated(bookingDepositsCreated);
                if (hasWarnings) details.setWarnings(warnings);
                job.setErrors(JOB_DETAILS_MAPPER.writeValueAsString(details));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize bulk-import counters into job.errors", e);
            }
        }

        importJobRepository.save(job);
    }

    private static final ObjectMapper JOB_DETAILS_MAPPER = new ObjectMapper();

    private Map<String, List<ChequeRow>> readChequesSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet("Cheques");
        if (sheet == null) return Collections.emptyMap();
        HeaderIndex hi = new HeaderIndex(sheet);
        Map<String, List<ChequeRow>> byKey = new HashMap<>();
        for (int r = 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null || isRowEmpty(row)) continue;
            String pname = cell(row, hi, "PropertyName");
            String unum = cell(row, hi, "UnitNumber");
            String email = cell(row, hi, "RenterEmail");
            int installmentNo;
            try { installmentNo = Integer.parseInt(cell(row, hi, "InstallmentNo")); }
            catch (NumberFormatException e) {
                log.debug("Cheques row {} dropped: InstallmentNo not numeric (validator should have flagged)", r + 1);
                continue;
            }
            LocalDate dueDate;
            try { dueDate = LocalDate.parse(cell(row, hi, "DueDate")); }
            catch (DateTimeParseException e) {
                log.debug("Cheques row {} dropped: DueDate not ISO (validator should have flagged)", r + 1);
                continue;
            }
            String chequeOrPaymentDateStr = cell(row, hi, "ChequeOrPaymentDate");
            LocalDate chequeOrPaymentDate = null;
            if (!chequeOrPaymentDateStr.isEmpty()) {
                try { chequeOrPaymentDate = LocalDate.parse(chequeOrPaymentDateStr); }
                catch (DateTimeParseException ignored) {
                    log.debug("Cheques row {} ChequeOrPaymentDate not ISO (validator should have flagged)", r + 1);
                }
            }
            String uniqueId = cell(row, hi, "UniqueId");
            String bank = cell(row, hi, "Bank");
            BigDecimal amount;
            try { amount = new BigDecimal(cell(row, hi, "Amount")); }
            catch (NumberFormatException e) {
                log.debug("Cheques row {} dropped: Amount not numeric (validator should have flagged)", r + 1);
                continue;
            }
            String method = cell(row, hi, "Method").toUpperCase();
            ChequeRow ch = new ChequeRow(installmentNo, dueDate, chequeOrPaymentDate,
                    uniqueId.isEmpty() ? null : uniqueId,
                    bank.isEmpty() ? null : bank,
                    amount,
                    method.isEmpty() ? null : method);
            byKey.computeIfAbsent(leaseKey(pname, unum, email), k -> new ArrayList<>()).add(ch);
        }
        return byKey;
    }

    private static String leaseKey(String propertyName, String unitNumber, String renterEmail) {
        return propertyName.trim().toLowerCase(Locale.ROOT)
                + "|" + unitNumber.trim().toLowerCase(Locale.ROOT)
                + "|" + renterEmail.trim().toLowerCase(Locale.ROOT);
    }

    /** Cheques-sheet row, with the lease's PaymentMethod already substituted in when blank. */
    private record ChequeRow(int installmentNo, LocalDate dueDate, LocalDate chequeOrPaymentDate,
                             String uniqueId, String bank, BigDecimal amount, String method) {}

    /** A lease line for an imported money column, named by catalogue code. */
    private static LeaseLineInput line(String code, BigDecimal gross, boolean vat,
                                       LocalDate periodStart, LocalDate periodEnd) {
        return new LeaseLineInput(null, code, gross, BigDecimal.ZERO, null, vat, null, periodStart, periodEnd);
    }

    /**
     * End-date-inclusive month count. Delegates to {@link DateMath#monthsInclusive}
     * so there is a single definition shared with the lease scheduling path.
     */
    static long monthsInclusive(LocalDate startDate, LocalDate endDate) {
        return DateMath.monthsInclusive(startDate, endDate);
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
