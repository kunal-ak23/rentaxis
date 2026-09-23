package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cheque.ChequeRowRules;
import com.datagami.rentaxis.core.service.cutover.ContractImportLeasePoster;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
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
    private final ChequeGenerationService chequeGenerationService;
    private final PropertyAccountService propertyAccountService;
    private final ContractImportLeasePoster leasePoster;

    private static final Set<String> VALID_BOOLS_TRUE = Set.of("true", "yes", "1");
    private static final Set<String> VALID_BOOLS_FALSE = Set.of("false", "no", "0");

    @Transactional
    public PersistResult persistWorkbook(Workbook workbook, ImportJob job) {
        return persistWorkbook(workbook, job, List.of());
    }

    /**
     * A row the sheet marked ACTIVE, now a DRAFT lease with its grid, waiting for
     * the post phase.
     */
    public record PendingPost(UUID leaseId, int rowNum, String label) {}

    /**
     * What the persist phase leaves for the post phase: the leases to post, the
     * ACTIVE rows that could not even be offered for posting (and why), and the job
     * details already written, so the post phase adds to them rather than
     * re-deriving them.
     */
    public record PersistResult(List<PendingPost> toPost, PortfolioImportJobDetailsDTO details) {
        public static PersistResult none() {
            return new PersistResult(List.of(), new PortfolioImportJobDetailsDTO());
        }
    }

    /**
     * Same as {@link #persistWorkbook(Workbook, ImportJob)} but additionally folds the
     * supplied validation warnings into the {@link PortfolioImportJobDetailsDTO}
     * wrapper persisted in {@code job.errors}, so the controller can surface them.
     */
    @Transactional
    public PersistResult persistWorkbook(Workbook workbook, ImportJob job, List<ImportErrorDTO> warnings) {
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

            Property saved = propertyRepository.save(p);
            // The property's own ledger leaves (spec §5.3), exactly as Add Property
            // makes them — PropertyService.createProperty calls the same method. The
            // import skipped this, so an imported building had no Rent Receivable,
            // no PDC Receivable and no income accounts, and none of its leases could
            // ever post (gap #83). Before the leases, because a lease line takes its
            // credit account from the property's mapping when it is applied.
            propertyAccountService.generateMissing(saved.getId());
            propertyMap.put(p.getNameEn().toLowerCase(), saved);
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
        int chequesCreated = 0;
        int bookingDepositsCreated = 0;
        // The caller's warnings are the validation phase's; this phase adds its
        // own. The two-argument overload passes List.of(), so copy rather than
        // mutate what was handed in.
        List<ImportErrorDTO> allWarnings = new ArrayList<>(warnings == null ? List.of() : warnings);
        // Rows the persist phase could not write. Errors rather than warnings: the
        // admin asked for these instruments and did not get them.
        List<ImportErrorDTO> allErrors = new ArrayList<>();
        List<PendingPost> toPost = new ArrayList<>();
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
                    : rentFromMonthly(new BigDecimal(monthlyRentStr), monthsBetween);

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
            boolean wantsActive = !"DRAFT".equals(statusStr);
            // Every lease is written DRAFT. Posting is the only way a lease becomes
            // ACTIVE (accounting v2): an ACTIVE row is handed to the post phase once
            // this transaction has committed, and becomes ACTIVE there — TCO, a PDR
            // per cheque, the unit claimed — or stays DRAFT and is listed with the
            // reason. This method used to set ACTIVE directly, which put tenancies on
            // the dashboard that were not on the books (gap #83).
            lease.setStatus(LeaseStatus.DRAFT);

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
            // LeaseCharge rows plus hand-built instalment rows was a second
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
            ChequeRowInput bookingDeposit = null;
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
                bookingDeposit = new ChequeRowInput(null, null, lease.getContractDate(), bdNum,
                        LocalDate.parse(bdDateStr), bdBank, null, null,
                        parseDecimalOrZero(bdAmt), "Booking Deposit", ChequeMode.PDC);
            }

            // ---- the cheque grid ----
            //
            // One grid that covers every line of the contract, because posting
            // requires Σ cheques = contract value and an import is not finished until
            // its leases can post:
            //   - a Cheques sheet states the rent instalments the renter handed over,
            //     so its rows are written as typed (UniqueId is the cheque number);
            //     the deposit and the fees it does not mention become rows of their
            //     own, dated the contract date, as the generator writes them;
            //   - with no sheet the whole grid is generated from the lease's lines and
            //     payment terms, deposit and fees folded into cheque 1 — what the
            //     draft wizard does;
            //   - a booking cheque is money already paid toward what is due at
            //     signing, so it is taken off the generated rows (never off a row the
            //     sheet stated) and stands as an instrument of its own.
            // The sheet used to be the whole grid, so a lease with a deposit could
            // never post: 101 held 38,000 of cheques on a 42,800 contract.
            String chequesKey = leaseKey(propertyName, unitNumber, renterEmail);
            List<ChequeRow> chequeRows = chequesByLeaseKey.getOrDefault(chequesKey, List.of());
            ChequeMode leaseMode = chequeMode(paymentMethodStr);
            ChequeMode signingMode = depMethod.isEmpty() ? leaseMode : chequeMode(depMethod);
            List<ChequeRowInput> sheetRows = new ArrayList<>();
            for (ChequeRow ch : chequeRows) {
                sheetRows.add(chequeInput(ch, savedLease));
            }
            if (!chequeRows.isEmpty()) {
                savedLease.setPaymentTerms(chequeRows.size());
            }

            String gridProblem = null;
            List<ChequeRowInput> generated = new ArrayList<>();
            boolean canGenerate = !chequeRows.isEmpty()
                    || (savedLease.getPaymentTerms() != null && savedLease.getPaymentTerms() >= 1);
            if (canGenerate) {
                ChequeGenerationService.Proposal proposal = chequeGenerationService.proposeForSystemImport(
                        savedLease,
                        new GenerateChequesRequest(savedLease.getPaymentTerms(), startDate,
                                null, null, null, null, null),
                        chequeRows.isEmpty());
                if (proposal.problem() != null) {
                    gridProblem = proposal.problem();
                } else {
                    // Rent instalments take the lease's method; the rows due at
                    // signing (deposit, fees) take DepositPaymentMethod when given.
                    for (ChequeGenerationService.Row r : proposal.rows()) {
                        boolean atSigning = !chequeRows.isEmpty() || !r.narration().startsWith("Rent");
                        generated.add(new ChequeRowInput(null, null, r.postingDate(), null, r.chequeDate(),
                                null, null, null, r.amount(), r.narration(),
                                atSigning ? signingMode : leaseMode));
                    }
                }
            } else {
                gridProblem = "the row has no PaymentTerms and no Cheques-sheet rows, so there is no grid";
            }
            if (bookingDeposit != null && gridProblem == null) {
                gridProblem = takeBookingOff(generated, bookingDeposit.amount());
            }

            List<ChequeRowInput> gridRows = new ArrayList<>(sheetRows);
            gridRows.addAll(generated);
            if (bookingDeposit != null) {
                // Last, as the contract prints it: the booking cheque is an extra
                // instrument alongside the instalment schedule, not part of it.
                gridRows.add(bookingDeposit);
            }

            int chequesOnThisLease = 0;
            if (!gridRows.isEmpty()) {
                // Validated up front with the pure rules rather than by catching what
                // saveRowsFor throws: a BusinessRuleViolationException out of a nested
                // @Transactional call marks this whole transaction rollback-only, so
                // "report the bad lease and import the rest" would instead lose the
                // entire workbook at commit time.
                String problem = rowRuleProblem(gridRows);
                if (problem != null) {
                    allErrors.add(new ImportErrorDTO("Cheques", i + 1, "UniqueID",
                            "The cheque rows for this lease were not imported: " + problem));
                    gridProblem = gridProblem != null ? gridProblem : "its cheque rows were refused: " + problem;
                } else {
                    chequeGenerationService.saveRowsForSystemImport(savedLease, gridRows);
                    chequesOnThisLease = gridRows.size();
                    chequesFromSheet += chequeRows.size();
                }
            }
            chequesCreated += chequesOnThisLease;
            savedLease = leaseRepository.save(savedLease);

            // The unit stays VACANT here whatever the row asked for: the post claims
            // it, so a row that does not post never shows as occupied.
            String label = unitNumber + " / " + renterEmail;
            if (wantsActive) {
                if (gridProblem == null) {
                    toPost.add(new PendingPost(savedLease.getId(), i + 1, label));
                } else {
                    allWarnings.add(importedAsDraft(i + 1, gridProblem));
                }
            }
        }

        job.setLeasesCreated(leasesCreated);
        // The column is still called schedules_created; what it counts is the cheque
        // rows the import wrote. Renaming it would be a migration for a number the
        // controller already reports under its new name.
        job.setSchedulesCreated(chequesCreated);

        // Persist the new counters and any warnings via the existing JSONB `errors`
        // column using the PortfolioImportJobDetailsDTO wrapper. Avoids a DB migration;
        // the controller reads either the legacy array form (validation-failed jobs)
        // or this wrapper. Wrapper is written when there's anything to surface
        // beyond the legacy fields.
        boolean hasWarnings = !allWarnings.isEmpty();
        boolean hasErrors = !allErrors.isEmpty();
        PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
        if (chequesFromSheet > 0) details.setChequesFromSheet(chequesFromSheet);
        if (bookingDepositsCreated > 0) details.setBookingDepositsCreated(bookingDepositsCreated);
        if (hasWarnings) details.setWarnings(allWarnings);
        if (hasErrors) details.setErrors(allErrors);
        if (chequesFromSheet > 0 || bookingDepositsCreated > 0 || hasWarnings || hasErrors) {
            writeDetails(job, details);
        }

        importJobRepository.save(job);
        return new PersistResult(List.copyOf(toPost), details);
    }

    /**
     * The post phase: every row the sheet marked ACTIVE, posted through the same
     * door a cut-over contract uses ({@link ContractImportLeasePoster}), one
     * transaction per lease.
     *
     * <p><b>Not transactional, on purpose.</b> It runs after
     * {@link #persistWorkbook} has committed — the poster's {@code REQUIRES_NEW}
     * transaction could not see the leases otherwise — and each lease commits or
     * rolls back on its own: one lease with a grid that does not add up costs that
     * lease, which stays DRAFT and is listed as "Imported as draft: …" with the
     * posting's own refusal, and nothing else. The caller installs the uploader's
     * authentication first; posting is access-checked.</p>
     */
    public void postRequested(PersistResult persisted, ImportJob job) {
        PortfolioImportJobDetailsDTO details = persisted.details();
        List<ImportErrorDTO> warnings = details.getWarnings() == null
                ? new ArrayList<>() : new ArrayList<>(details.getWarnings());
        int posted = 0;
        for (PendingPost p : persisted.toPost()) {
            try {
                leasePoster.postPortfolioLease(p.leaseId());
                posted++;
            } catch (RuntimeException e) {
                log.info("Imported lease {} (row {}) left as draft: {}", p.leaseId(), p.rowNum(), e.getMessage());
                warnings.add(importedAsDraft(p.rowNum(), e.getMessage()));
            }
        }
        details.setLeasesPosted(posted);
        details.setWarnings(warnings.isEmpty() ? null : warnings);
        writeDetails(job, details);
        importJobRepository.save(job);
    }

    /** "Imported as draft: <reason>", against the Leases row — never a silent DRAFT. */
    private static ImportErrorDTO importedAsDraft(int rowNum, String reason) {
        String why = reason == null || reason.isBlank() ? "it could not be posted" : reason;
        return new ImportErrorDTO("Leases", rowNum, "Status", "Imported as draft: " + why);
    }

    private static void writeDetails(ImportJob job, PortfolioImportJobDetailsDTO details) {
        try {
            job.setErrors(JOB_DETAILS_MAPPER.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize bulk-import counters into job.errors", e);
        }
    }

    /**
     * Take a booking cheque off the rows the import generated, first row first; a
     * row brought to zero is dropped.
     *
     * @return why it could not be placed, or null. It can only fail when the booking
     *         cheque is worth more than everything the import generated — with a
     *         Cheques sheet, more than the deposit and fees together.
     */
    static String takeBookingOff(List<ChequeRowInput> generated, BigDecimal booking) {
        BigDecimal left = booking;
        for (int k = 0; k < generated.size() && left.signum() > 0; k++) {
            ChequeRowInput r = generated.get(k);
            BigDecimal take = r.amount().min(left);
            left = left.subtract(take);
            BigDecimal rest = r.amount().subtract(take);
            generated.set(k, new ChequeRowInput(r.id(), r.seqNo(), r.postingDate(), r.chequeNumber(),
                    r.chequeDate(), r.payeeBank(), r.payerName(), r.debitAccountId(), rest, r.narration(), r.mode()));
        }
        generated.removeIf(r -> r.amount().signum() == 0);
        if (left.signum() > 0) {
            return "the booking deposit (" + booking.toPlainString() + ") is more than the rows it pays toward";
        }
        return null;
    }

    /**
     * Term rent from a MonthlyRent cell, without the rounding leak.
     *
     * <p>A monthly figure in a sheet is usually an annual rent divided by twelve and
     * rounded to the fils: 98,000 / 12 is typed as 8,166.67, and 8,166.67 × 12 =
     * 98,000.04 — four fils of rent nobody agreed to (gap #83). The rule: when some
     * <b>whole-dirham</b> term rent has a monthly share that rounds to exactly the
     * typed figure, the term rent is that one (the roundest, trying thousands,
     * hundreds, tens, then units); otherwise it is MonthlyRent × months exactly.
     * 8,166.67 × 12 → 98,000.00; 5,000 × 12 → 60,000.00; 1,000.01 × 12 → 12,000.12
     * (no whole dirham divides back to 1,000.01, so the fils were meant).</p>
     */
    static BigDecimal rentFromMonthly(BigDecimal monthly, long months) {
        BigDecimal n = BigDecimal.valueOf(months);
        BigDecimal product = monthly.multiply(n).setScale(2, RoundingMode.HALF_UP);
        if (monthly.stripTrailingZeros().scale() > 2) {
            // Typed with more than two decimals: the typist was not rounding to the
            // fils, so there is no rounded division to undo.
            return product;
        }
        for (int scale = -3; scale <= 0; scale++) {
            BigDecimal candidate = product.setScale(scale, RoundingMode.HALF_UP).setScale(2, RoundingMode.UNNECESSARY);
            if (candidate.divide(n, 2, RoundingMode.HALF_UP).compareTo(monthly) == 0) {
                return candidate;
            }
        }
        return product;
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

    /**
     * One Cheques-sheet row as a grid row.
     *
     * <p>{@code ChequeOrPaymentDate} is the date written on the instrument when the
     * sheet gives one; {@code DueDate} is the fallback, since an instalment with no
     * instrument date still matures when the schedule says. The posting date is the
     * lease's contract date, as it is for a grid the wizard generates.</p>
     *
     * <p>A cheque number only travels on a PDC row: {@code ChequeRowRules} refuses
     * one on a cash or transfer receipt, which is right — a bank transfer has a
     * reference, not a cheque number — so a sheet that put a reference in the
     * UniqueID column of a CASH row has it dropped rather than the lease refused.</p>
     */
    private ChequeRowInput chequeInput(ChequeRow ch, Lease lease) {
        ChequeMode mode = chequeMode(ch.method());
        LocalDate chequeDate = ch.chequeOrPaymentDate() != null ? ch.chequeOrPaymentDate() : ch.dueDate();
        return new ChequeRowInput(
                null, null,
                lease.getContractDate(),
                mode == ChequeMode.PDC ? ch.uniqueId() : null,
                chequeDate,
                ch.bank(),
                null, null,
                ch.amount(),
                "Rent - " + ChequeGenerationService.ordinal(ch.installmentNo()) + " Installment",
                mode);
    }

    /** The sheet's PaymentMethod vocabulary as register modes. ONLINE is never typed in. */
    private static ChequeMode chequeMode(String method) {
        if (method == null || method.isBlank()) return ChequeMode.PDC;
        return switch (method.trim().toUpperCase(Locale.ROOT)) {
            case "CASH" -> ChequeMode.CASH;
            case "BANK_TRANSFER", "TRANSFER" -> ChequeMode.TRANSFER;
            default -> ChequeMode.PDC;
        };
    }

    /**
     * The row rules, run without a transaction so a violation can be reported
     * instead of thrown.
     *
     * @return the refusal, or null when every row is acceptable.
     */
    private static String rowRuleProblem(List<ChequeRowInput> rows) {
        try {
            ChequeRowRules.validateNewRows(rows, Set.of(), "cheque");
            return null;
        } catch (BusinessRuleViolationException e) {
            return e.getMessage();
        }
    }

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
