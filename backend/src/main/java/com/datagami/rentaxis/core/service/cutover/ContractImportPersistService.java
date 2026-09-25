package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.PortfolioImportJobDetailsDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.SheetCells;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportJob;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.ImportJobRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes a validated cut-over workbook: properties with their role mappings,
 * units, renters, DRAFT leases with their lines, and DRAFT cheque rows — all
 * linked to one {@link ImportBatch}.
 *
 * <p><b>Nothing posts here.</b> Not one journal, not one status change. Posting is
 * a separate, explicit act on the batch (spec §10.3 "Bulk post"), and that split is
 * the whole reason the import is reversible: a landlord can load six hundred
 * contracts, read them on screen, correct the workbook and load it again, all
 * before a single number reaches the ledger.</p>
 *
 * <p><b>One transaction, and it has already been validated.</b>
 * {@link ContractImportValidator} has answered every question this method would
 * otherwise have to answer halfway through — the accounts resolve, the totals
 * agree, the charge types exist, no unit is let twice — so the code below reads
 * cells it knows are good. Where it does still ask, it asks in a way that reports
 * rather than throws, because an exception out of a nested transactional call
 * marks this transaction rollback-only and loses the whole workbook rather than
 * one row of it.</p>
 *
 * <p><b>Through the existing services, not around them</b> (review R9).
 * {@code LeaseService.applyLines} resolves each line's credit account exactly as
 * the draft wizard does; {@code ChequeGenerationService.saveRowsForSystemImport}
 * writes the grid through {@code ChequeRowRules}, which is what turns a duplicate
 * cheque number into a sentence instead of a unique-index violation. A second
 * implementation of either is how two doors onto one ledger come to disagree.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractImportPersistService {

    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final ChequeRepository chequeRepository;
    private final AccountRepository accountRepository;
    private final ImportJobRepository importJobRepository;
    private final LeaseService leaseService;
    private final ChargeTypeService chargeTypeService;
    private final ChequeGenerationService chequeGenerationService;
    private final PropertyAccountService propertyAccounts;
    private final ImportBatchService batches;

    private static final ObjectMapper JOB_DETAILS_MAPPER = new ObjectMapper();

    /** What the import wrote, for the job row and for the caller's log line. */
    public record ContractImportSummary(UUID batchId, int propertiesCreated, int buildingsCreated,
                                        int unitsCreated, int rentersCreated, int contractsCreated,
                                        int chequesCreated, int mappingsCreated,
                                        List<ImportErrorDTO> warnings) {}

    @Transactional
    public ContractImportSummary persist(Workbook wb, ImportJob job) {
        return persist(wb, job, List.of());
    }

    /**
     * @param validationWarnings what the validator reported; this phase adds its own,
     *                           and both end up on the job for the screen to show.
     */
    @Transactional
    public ContractImportSummary persist(Workbook wb, ImportJob job, List<ImportErrorDTO> validationWarnings) {
        requireTenant();
        List<ImportErrorDTO> warnings = new ArrayList<>(validationWarnings == null ? List.of() : validationWarnings);

        // The lines are built by catalogue code, so the catalogue has to exist first.
        // Idempotent, and it leaves any row the tenant has edited alone — the same
        // call, for the same reason, that the v1 importer makes.
        chargeTypeService.seedDefaults();

        ImportBatch batch = batches.create(job.getId(), batchLabel(job));
        Map<String, Account> accountsByName = chartByName();

        Properties properties = writeProperties(wb, accountsByName, warnings, batch);
        Units units = writeUnits(wb, properties.byName(), batch);
        Map<String, Renter> rentersByEmail = writeRenters(wb, batch);
        Contracts contracts = writeContracts(wb, properties.byName(), units.byKey(), rentersByEmail,
                accountsByName, batch);
        int chequesCreated = writeCheques(wb, contracts, accountsByName);

        // Roles nobody named and no template covers: reported, never guessed. A lease
        // whose role is unmapped imports fine and refuses to post, and finding that
        // out now is the difference between one screen of warnings and six hundred
        // failures in the bulk post.
        reportUnmappedRoles(properties, contracts, warnings);

        job.setPropertiesCreated(properties.byName().size());
        job.setBuildingsCreated(units.buildings());
        job.setUnitsCreated(units.byKey().size());
        job.setRentersCreated(rentersByEmail.size());
        job.setLeasesCreated(contracts.byNumber().size());
        // The column is still called schedules_created; what it counts is the cheque
        // rows the import wrote, which is what the controller reports it as.
        job.setSchedulesCreated(chequesCreated);
        job.setImportBatchId(batch.getId());

        PortfolioImportJobDetailsDTO details = new PortfolioImportJobDetailsDTO();
        details.setWarnings(warnings);
        details.setContractsCreated(contracts.byNumber().size());
        details.setChequesFromSheet(chequesCreated);
        details.setMappingsCreated(properties.mappingsCreated());
        try {
            job.setErrors(JOB_DETAILS_MAPPER.writeValueAsString(details));
        } catch (Exception e) {
            log.warn("Could not serialise contract-import details for job {}", job.getId(), e);
        }
        importJobRepository.save(job);

        return new ContractImportSummary(batch.getId(), properties.byName().size(), units.buildings(),
                units.byKey().size(), rentersByEmail.size(), contracts.byNumber().size(),
                chequesCreated, properties.mappingsCreated(), warnings);
    }

    private static String batchLabel(ImportJob job) {
        String file = job.getFileName();
        return "Contract import — " + (file == null || file.isBlank() ? "workbook" : file);
    }

    /**
     * The tenant's chart indexed by name.
     *
     * <p>A name the chart answers to twice is left out entirely rather than resolved
     * to one of them: the validator has already refused the workbook over it, and if
     * one ever reached here, importing a whole building's rent into an arbitrary one
     * of two same-named leaves is worse than leaving the role for the template.</p>
     */
    private Map<String, Account> chartByName() {
        Map<String, Account> byName = new HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        for (Account a : accountRepository.findAll()) {
            if (a.getName() == null || a.isGroup()) continue;
            String key = a.getName().trim().toLowerCase(Locale.ROOT);
            if (byName.put(key, a) != null) ambiguous.add(key);
        }
        ambiguous.forEach(byName::remove);
        return byName;
    }

    private Account account(Map<String, Account> byName, String name) {
        return name == null || name.isBlank() ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // 1. Properties, and the six account columns
    // ------------------------------------------------------------------

    private record Properties(Map<String, Property> byName, int mappingsCreated) {}

    private Properties writeProperties(Workbook wb, Map<String, Account> accountsByName,
                                       List<ImportErrorDTO> warnings, ImportBatch batch) {
        Sheet sheet = wb.getSheet("Properties");
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);
        Map<String, Property> byName = new LinkedHashMap<>();
        int mappingsCreated = 0;

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            Property p = new Property();
            p.setNameEn(SheetCells.getCellString(row, 0));
            p.setNameAr(blankToNull(SheetCells.getCellString(row, 1)));
            p.setEmirate(enumOf(Emirate.class, SheetCells.getCellString(row, 2)));
            p.setAddress(blankToNull(SheetCells.getCellString(row, 3)));
            p.setType(enumOf(PropertyType.class, SheetCells.getCellString(row, 4)));
            p.setMakaniNumber(blankToNull(SheetCells.getCellString(row, 5)));
            Property saved = propertyRepository.save(p);
            byName.put(saved.getNameEn().toLowerCase(Locale.ROOT), saved);
            // Written down now, because only this code knows it made this row. A
            // discard reconstructed later from timestamps would take somebody else's.
            batches.linkEntity(batch.getId(), ImportedEntityType.PROPERTY, saved.getId());

            // The sheet's names are written FIRST and the template fills the rest
            // afterwards, which is the only order that means what it says:
            // generateMissing skips a role that already has a mapping, so writing it
            // first would have the template's generated leaf win over the account the
            // accountant actually named — and leave a leaf nothing posts to behind.
            for (Map.Entry<String, AccountRole> e : ContractImportValidator.ACCOUNT_COLUMN_ROLES.entrySet()) {
                String accountName = SheetCells.cell(row, hi, e.getKey());
                Account account = account(accountsByName, accountName);
                if (account == null) {
                    if (!accountName.isBlank()) {
                        // The validator refuses this, so reaching it means the two
                        // disagree; say so rather than mapping the role to nothing.
                        warnings.add(new ImportErrorDTO("Properties", rowNum, e.getKey(),
                                "No ledger account named '" + accountName + "'; " + e.getValue()
                                        + " was left to the account template"));
                    }
                    continue;
                }
                propertyAccounts.setMapping(saved.getId(), e.getValue(), account.getId());
                mappingsCreated++;
            }
            propertyAccounts.generateMissing(saved.getId());
        }
        return new Properties(byName, mappingsCreated);
    }

    // ------------------------------------------------------------------
    // 2. Buildings and units — the v1 rules, unchanged
    // ------------------------------------------------------------------

    private record Units(Map<String, Unit> byKey, int buildings) {}

    private Units writeUnits(Workbook wb, Map<String, Property> propertyByName, ImportBatch batch) {
        Sheet sheet = wb.getSheet("Units");
        Map<String, Building> buildingByKey = new LinkedHashMap<>();
        Map<String, Unit> byKey = new LinkedHashMap<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;

            String propertyName = SheetCells.getCellString(row, 0);
            String buildingName = SheetCells.getCellString(row, 1);
            String unitNumber = SheetCells.getCellString(row, 2);
            String unitType = SheetCells.getCellString(row, 3);
            String sizeSqft = SheetCells.getCellString(row, 4);
            String expectedRent = SheetCells.getCellString(row, 5);
            Property property = propertyByName.get(propertyName.toLowerCase(Locale.ROOT));

            Building building = null;
            if (!buildingName.isEmpty()) {
                String bKey = (propertyName + "|" + buildingName).toLowerCase(Locale.ROOT);
                building = buildingByKey.get(bKey);
                if (building == null) {
                    Building b = new Building();
                    b.setProperty(property);
                    b.setNameEn(buildingName);
                    building = buildingRepository.save(b);
                    buildingByKey.put(bKey, building);
                    // Recorded the moment it is created, and only then. Every building
                    // this loop makes is one this import made — a cut-over property is
                    // always new (the validator refuses a name the organisation already
                    // has), so it can own no pre-existing towers — but the second flat
                    // in the same tower reuses the row above and must not claim to have
                    // made it a second time. `linkEntity` is idempotent, so the
                    // distinction costs nothing; it is here so the code says which case
                    // it is in rather than relying on the primary key to forgive it.
                    batches.linkEntity(batch.getId(), ImportedEntityType.BUILDING, building.getId());
                }
            }

            Unit u = new Unit();
            u.setProperty(property);
            u.setBuilding(building);
            u.setUnitNumber(unitNumber);
            // VACANT, and it stays that way until the batch posts: a DRAFT lease
            // reserves nothing, exactly as a lease drafted in the wizard does not.
            u.setStatus(UnitStatus.VACANT);
            if (!unitType.isEmpty()) u.setType(enumOf(UnitType.class, unitType));
            if (!sizeSqft.isEmpty()) u.setSizeSqft(new BigDecimal(sizeSqft));
            if (!expectedRent.isEmpty()) u.setExpectedRent(new BigDecimal(expectedRent));

            Unit savedUnit = unitRepository.save(u);
            batches.linkEntity(batch.getId(), ImportedEntityType.UNIT, savedUnit.getId());
            byKey.put(ContractImportValidator.unitKey(propertyName, buildingName, unitNumber), savedUnit);
        }
        return new Units(byKey, buildingByKey.size());
    }

    // ------------------------------------------------------------------
    // 3. Renters
    // ------------------------------------------------------------------

    private Map<String, Renter> writeRenters(Workbook wb, ImportBatch batch) {
        Sheet sheet = wb.getSheet("Renters");
        Map<String, Renter> byEmail = new LinkedHashMap<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            Renter r = new Renter();
            r.setNameEn(SheetCells.getCellString(row, 0));
            r.setNameAr(blankToNull(SheetCells.getCellString(row, 1)));
            r.setEmail(SheetCells.getCellString(row, 2));
            r.setPhone(blankToNull(SheetCells.getCellString(row, 3)));
            Renter saved = renterRepository.save(r);
            batches.linkEntity(batch.getId(), ImportedEntityType.RENTER, saved.getId());
            byEmail.put(saved.getEmail().toLowerCase(Locale.ROOT), saved);
        }
        return byEmail;
    }

    // ------------------------------------------------------------------
    // 4. Contracts -> DRAFT leases + their lines
    // ------------------------------------------------------------------

    private record Contracts(Map<String, Lease> byNumber, Set<UUID> propertyIds,
                             Set<AccountRole> rolesByLine, boolean anyVat) {}

    private Contracts writeContracts(Workbook wb, Map<String, Property> propertyByName,
                                     Map<String, Unit> unitByKey, Map<String, Renter> renterByEmail,
                                     Map<String, Account> accountsByName, ImportBatch batch) {
        Sheet sheet = wb.getSheet("Contracts");
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);

        // Lines are gathered per contract first and applied in one call, because
        // LeaseService.applyLines replaces the whole set: calling it per row would
        // leave each lease with only its last line.
        Map<String, Lease> leaseByNumber = new LinkedHashMap<>();
        Map<String, List<LeaseLineInput>> linesByNumber = new LinkedHashMap<>();
        Set<UUID> propertyIds = new java.util.LinkedHashSet<>();
        Set<AccountRole> rolesByLine = EnumSet.noneOf(AccountRole.class);
        boolean anyVat = false;

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            String number = SheetCells.cell(row, hi, "ContractNumber");

            Lease lease = leaseByNumber.get(number);
            if (lease == null) {
                lease = newDraftLease(row, hi, number, propertyByName, unitByKey, renterByEmail);
                leaseByNumber.put(number, lease);
                linesByNumber.put(number, new ArrayList<>());
                batches.linkLease(batch.getId(), lease.getId());
                if (lease.getUnit() != null && lease.getUnit().getProperty() != null) {
                    propertyIds.add(lease.getUnit().getProperty().getId());
                }
            }

            BigDecimal gross = new BigDecimal(SheetCells.cell(row, hi, "GrossAmount").trim());
            String discountRaw = SheetCells.cell(row, hi, "DiscountAmount");
            BigDecimal discount = discountRaw.isBlank() ? BigDecimal.ZERO : new BigDecimal(discountRaw.trim());
            boolean vat = Boolean.TRUE.equals(ContractImportValidator.boolOrNull(
                    SheetCells.cell(row, hi, "VatApplicable")));
            anyVat |= vat;

            Account credit = account(accountsByName, SheetCells.cell(row, hi, "CreditAccount"));
            linesByNumber.get(number).add(new LeaseLineInput(
                    null,
                    SheetCells.cell(row, hi, "ChargeTypeCode").trim(),
                    gross,
                    discount,
                    blankToNull(SheetCells.cell(row, hi, "Narration")),
                    vat,
                    credit == null ? null : credit.getId(),
                    null, null));
        }

        // The lines, then the totals the lease mirrors — the same two calls, in the
        // same order, that the draft wizard and the v1 importer both make.
        for (Map.Entry<String, Lease> e : leaseByNumber.entrySet()) {
            Lease lease = e.getValue();
            leaseService.applyLines(lease, linesByNumber.get(e.getKey()));
            leaseService.syncDerivedTotals(lease);
            leaseRepository.save(lease);
        }
        for (LeaseLineInput in : linesByNumber.values().stream().flatMap(List::stream).toList()) {
            if (in.creditAccountId() != null) continue;
            var seeded = ChargeTypeService.seededDefault(in.chargeTypeCode());
            if (seeded != null) rolesByLine.add(seeded.role());
        }
        return new Contracts(leaseByNumber, propertyIds, rolesByLine, anyVat);
    }

    /**
     * The lease row itself.
     *
     * <p>Built here rather than through {@code LeaseService.createDraftLease}
     * deliberately, and the v1 importer makes the same choice for the same reason:
     * that method publishes a {@code LEASE_CREATED} transactional email to the
     * renter and the property manager. A cut-over is six hundred contracts that have
     * been running for months, and its first visible effect must not be six hundred
     * people being told their lease was just created. The lines still go through
     * {@code LeaseService}, which is where the rules that matter live.</p>
     */
    private Lease newDraftLease(Row row, SheetCells.HeaderIndex hi, String number,
                                Map<String, Property> propertyByName, Map<String, Unit> unitByKey,
                                Map<String, Renter> renterByEmail) {
        String propertyName = SheetCells.cell(row, hi, "PropertyName");
        String buildingName = SheetCells.cell(row, hi, "BuildingName");
        String unitNumber = SheetCells.cell(row, hi, "UnitNumber");
        Unit unit = unitByKey.get(ContractImportValidator.unitKey(propertyName, buildingName, unitNumber));
        Renter renter = renterByEmail.get(
                SheetCells.cell(row, hi, "RenterEmail").trim().toLowerCase(Locale.ROOT));

        Lease lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStatus(LeaseStatus.DRAFT);
        // PACT's own reference, in its own column. leases.contract_number is a Long
        // fed by our sequence and is not the place for "TLP7/681"; this is what the
        // accountant searches by and what a re-import after a reverse finds.
        lease.setExternalContractRef(number);
        lease.setEjariNumber(blankToNull(SheetCells.cell(row, hi, "EjariNumber")));
        lease.setContractDate(date(row, hi, "ContractDate"));
        lease.setStartDate(date(row, hi, "StartDate"));
        lease.setEndDate(date(row, hi, "EndDate"));
        lease.setFirstDueDate(lease.getStartDate());
        // PACT declared this contract's VAT on its contract date, so it stays on the
        // CONTRACT model: the TCO credits Output VAT in full, with no tax points and
        // no tax invoices of ours (spec 2026-09-24 §1, cut-over ruling).
        lease.setVatTiming(com.datagami.rentaxis.domain.entity.enums.VatTiming.CONTRACT);
        // F14-18: PACT booked every fee as income on the contract date; the replay
        // keeps that (the golden gate stays byte-identical).
        lease.setFeeTiming(com.datagami.rentaxis.domain.entity.enums.FeeTiming.AT_POSTING);
        // A blank cell is the property's default, not zero (gap #65).
        String grace = SheetCells.cell(row, hi, "GracePeriodDays");
        leaseService.applyGracePeriod(lease, grace.isBlank() ? null : Integer.parseInt(grace.trim()), unit);

        Lease saved = leaseRepository.save(lease);
        // chain_id cannot be set before the insert: it mirrors an id that does not
        // exist yet. A fresh chain per contract — PACT's history stays in PACT.
        if (saved.getChainId() == null) {
            saved.setChainId(saved.getId());
            saved = leaseRepository.save(saved);
        }
        return saved;
    }

    private static LocalDate date(Row row, SheetCells.HeaderIndex hi, String header) {
        return SheetCells.parseDateOrNull(SheetCells.cell(row, hi, header));
    }

    // ------------------------------------------------------------------
    // 5. Cheques -> DRAFT rows carrying what bulk post must replay
    // ------------------------------------------------------------------

    private int writeCheques(Workbook wb, Contracts contracts, Map<String, Account> accountsByName) {
        Sheet sheet = wb.getSheet("Cheques");
        if (sheet == null) return 0;
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);

        record SheetRow(int seqNo, ChequeRowInput input, ChequeStatus importedStatus,
                        LocalDate deposited, LocalDate cleared, LocalDate bounced) {}

        Map<String, List<SheetRow>> byContract = new LinkedHashMap<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            String number = SheetCells.cell(row, hi, "ContractNumber");
            Lease lease = contracts.byNumber().get(number);
            if (lease == null) continue;

            ChequeMode mode = SheetCells.cell(row, hi, "Mode").isBlank()
                    ? ChequeMode.PDC
                    : enumOf(ChequeMode.class, SheetCells.cell(row, hi, "Mode"));
            Account debit = account(accountsByName, SheetCells.cell(row, hi, "DebitAccount"));
            String statusRaw = SheetCells.cell(row, hi, "Status");
            ChequeStatus imported = statusRaw.isBlank()
                    ? ChequeStatus.REGISTERED
                    : enumOf(ChequeStatus.class, statusRaw);

            ChequeRowInput input = new ChequeRowInput(
                    null, null,
                    date(row, hi, "PostingDate"),
                    mode == ChequeMode.PDC ? blankToNull(SheetCells.cell(row, hi, "ChequeNumber")) : null,
                    date(row, hi, "ChequeDate"),
                    blankToNull(SheetCells.cell(row, hi, "PayeeBank")),
                    null,
                    debit == null ? null : debit.getId(),
                    new BigDecimal(SheetCells.cell(row, hi, "Amount").trim()),
                    blankToNull(SheetCells.cell(row, hi, "Narration")),
                    mode);

            LocalDate cleared = date(row, hi, "ClearedDate");
            LocalDate bounced = date(row, hi, "BouncedDate");
            byContract.computeIfAbsent(number, k -> new ArrayList<>()).add(new SheetRow(
                    Integer.parseInt(SheetCells.cell(row, hi, "SeqNo").trim()), input, imported,
                    // Through the validator's own helper, so the date this writes is
                    // the date the validator accepted and the constraint expects.
                    ContractImportValidator.depositedOnFor(
                            mode, imported, date(row, hi, "DepositedDate"), cleared, bounced),
                    cleared, bounced));
        }

        int created = 0;
        for (Map.Entry<String, List<SheetRow>> e : byContract.entrySet()) {
            Lease lease = contracts.byNumber().get(e.getKey());
            List<SheetRow> rows = new ArrayList<>(e.getValue());
            // saveRowsForSystemImport re-numbers positions 1..n in LIST order and
            // ignores the caller's seqNo, so the sheet's SeqNo has to become the
            // order of the list or the grid would come out in row order.
            rows.sort(Comparator.comparingInt(SheetRow::seqNo));

            // The grid is the instalment plan; the lease's payment terms are what the
            // sheet really listed rather than a number nobody typed.
            lease.setPaymentTerms(rows.size());
            leaseRepository.save(lease);

            chequeGenerationService.saveRowsForSystemImport(lease, rows.stream().map(SheetRow::input).toList());

            // Re-read in position order and stamp the replay instruction on each row.
            // By position, because that is what the writer just assigned and the only
            // thing that ties a stored row back to the sheet row it came from.
            List<Cheque> stored = chequeRepository.findByLease_IdOrderBySeqNoAsc(lease.getId());
            requireWholeGrid(e.getKey(), rows.size(), stored.size());
            for (int i = 0; i < stored.size(); i++) {
                Cheque c = stored.get(i);
                SheetRow r = rows.get(i);
                c.setImportedStatus(r.importedStatus());
                c.setImportedDepositedOn(r.deposited());
                c.setImportedClearedOn(r.cleared());
                c.setImportedBouncedOn(r.bounced());
            }
            chequeRepository.saveAll(stored);
            created += stored.size();
        }
        return created;
    }

    /**
     * The grid that came back must be the grid that went in.
     *
     * <p>The loop that follows stamps each stored row with the replay instruction
     * from the sheet row at the same position. If the two ever differ in length,
     * iterating the shorter of them would leave some cheques with no imported
     * status and no dates — and a batch that posts half its cheques on the days
     * they really moved and the rest on the day somebody clicked Post. Inside this
     * transaction, refusing loses the workbook, which is the correct outcome and a
     * loud one.</p>
     *
     * <p>A named method rather than an inline {@code if} so the invariant can be
     * exercised directly: nothing a test can do to a real
     * {@code ChequeGenerationService} makes the counts disagree, which is precisely
     * why the guard is worth having and why it needs its own door.</p>
     */
    static void requireWholeGrid(String contractNumber, int handed, int stored) {
        if (handed != stored) {
            throw new IllegalStateException("Contract " + contractNumber + " was handed " + handed
                    + " cheque rows but its grid holds " + stored
                    + "; refusing to stamp a partial grid");
        }
    }

    // ------------------------------------------------------------------
    // 6. What is still unmapped
    // ------------------------------------------------------------------

    /**
     * The roles an imported lease will need at post time that nothing has supplied.
     *
     * <p>Asked of the mappings as they now stand, rather than of the sheet: the
     * account template may cover a role the sheet left blank, and it may equally be
     * incomplete (issue #299 — never assume the default seed ran). PDC_RECEIVABLE
     * and RENT_RECEIVABLE are needed by every contract; a line's own role is needed
     * only where the line named no credit account of its own, which is the same rule
     * {@code LeasePostingService} applies.</p>
     */
    private void reportUnmappedRoles(Properties properties, Contracts contracts, List<ImportErrorDTO> warnings) {
        Set<AccountRole> needed = EnumSet.of(AccountRole.PDC_RECEIVABLE, AccountRole.RENT_RECEIVABLE,
                AccountRole.BANK);
        needed.addAll(contracts.rolesByLine());
        for (Property p : properties.byName().values()) {
            if (!contracts.propertyIds().contains(p.getId())) continue;
            var mapped = propertyAccounts.getMappings(p.getId()).stream()
                    .filter(m -> m.accountId() != null)
                    .map(m -> m.role())
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(AccountRole.class)));
            for (AccountRole role : needed) {
                if (mapped.contains(role)) continue;
                warnings.add(ImportErrorDTO.file("Properties", role.name(),
                        "'" + p.getNameEn() + "' has no account for " + role
                                + "; its contracts will import but cannot be posted until it does"));
            }
        }
        if (contracts.anyVat()) {
            warnings.add(ImportErrorDTO.file("Contracts", "VatApplicable",
                    "Some contracts charge VAT; the organisation needs an OUTPUT_VAT account"
                            + " mapped before they can be posted"));
        }
    }

    // ------------------------------------------------------------------
    // cells
    // ------------------------------------------------------------------

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw) {
        return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace(" ", "_"));
    }

    /**
     * Belt and braces on the one thing that would be catastrophic and silent: this
     * runs on the import executor's thread, where an absent tenant context means the
     * Hibernate filter is off and every write lands wherever Hibernate pleases.
     */
    private static void requireTenant() {
        if (TenantContextHolder.getTenantId() == null) {
            throw new IllegalStateException(
                    "Contract import ran without a tenant context; refusing to write unscoped rows");
        }
    }
}
