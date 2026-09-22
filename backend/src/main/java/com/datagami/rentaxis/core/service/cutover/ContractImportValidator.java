package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PortfolioImportService;
import com.datagami.rentaxis.core.service.SheetCells;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChargeTypeRepository;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.repository.ImportBatchEntityRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchLeaseRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Validates the accounting-v2 cut-over workbook (spec §10.3 step 1) before
 * anything at all is written.
 *
 * <p><b>A second validator, not a second importer.</b> It plugs into
 * {@code PortfolioImportService}'s existing async orchestrator, writes into the
 * same {@code import_jobs} row and reports through the same {@link ImportErrorDTO}
 * shape, so the polling screen, the error table and the failure handling are
 * unchanged. The only thing that differs is the sheet set and the persist target.</p>
 *
 * <p><b>Complete before anything is written.</b> A workbook with a single error
 * persists nothing, and the accountant gets every error in the file at once, each
 * naming its sheet, its 1-based row and its column. That is not politeness: a
 * cut-over workbook is assembled by hand from a PACT export, and an importer that
 * reports one fault per round trip turns a morning's work into a week's.</p>
 *
 * <p><b>Why {@code @Transactional(readOnly = true)}.</b> {@code TenantAspect}
 * enables the Hibernate tenant filter only inside a transaction, and this runs on
 * the import executor's thread, which has no request-bound session. An untransacted
 * repository read there runs with the filter <em>off</em> — a cross-tenant read,
 * which on this exact class is a P0. Everything the database is asked lives behind
 * {@link Lookups}, whose one production implementation is built inside that
 * transaction.</p>
 *
 * <p><b>Statuses.</b> The sheet may carry the subset of {@link ChequeStatus} that
 * bulk post can actually replay through {@code ChequeService} from a freshly
 * registered row: REGISTERED (what the lease post itself creates), DEPOSITED,
 * CLEARED and BOUNCED. A cheque that is REPLACED or RETURNED in PACT belongs to
 * closed history, which is out of scope (spec §14).</p>
 */
@Service
public class ContractImportValidator {

    /** What the replay can reach from a newly registered row. */
    static final Set<String> IMPORTABLE_STATUSES = Set.of("REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED");

    /** {@code leases.external_contract_ref} is varchar(64) (changeset 88). */
    static final int MAX_CONTRACT_REF = 64;

    /**
     * The Properties sheet's optional account columns, in the order the client's
     * "property mapping ledgers" export lists them, and the role each one fills.
     *
     * <p>Four of the six are that export's own columns; {@code PdcReceivableAccount}
     * and {@code SecurityDepositAccount} are the two further roles a lease needs
     * before it can post (spec §5.4) and are here so the accountant can supply them
     * in the same pass rather than in a second screen afterwards.</p>
     */
    static final Map<String, AccountRole> ACCOUNT_COLUMN_ROLES = new LinkedHashMap<>();
    static {
        ACCOUNT_COLUMN_ROLES.put("RentalIncomeAccount", AccountRole.RENTAL_INCOME);
        ACCOUNT_COLUMN_ROLES.put("RentalReceivableAccount", AccountRole.RENT_RECEIVABLE);
        ACCOUNT_COLUMN_ROLES.put("AdvanceRentAccount", AccountRole.ADVANCE_RENT);
        ACCOUNT_COLUMN_ROLES.put("BankAccount", AccountRole.BANK);
        ACCOUNT_COLUMN_ROLES.put("PdcReceivableAccount", AccountRole.PDC_RECEIVABLE);
        ACCOUNT_COLUMN_ROLES.put("SecurityDepositAccount", AccountRole.SECURITY_DEPOSIT);
    }

    private final ChargeTypeRepository chargeTypes;
    private final AccountRepository accounts;
    private final PropertyRepository properties;
    private final RenterRepository renters;
    private final UnitRepository units;
    private final LeaseRepository leases;
    private final ImportBatchLeaseRepository batchLeases;
    private final ImportBatchEntityRepository batchEntities;
    private final ImportBatchRepository batchRepository;
    private final PropertyAccountService propertyAccounts;

    /**
     * {@code @Autowired} is load-bearing, not decoration: there are two
     * constructors, and without it Spring takes the no-argument one and every
     * repository here is null at the first cut-over upload.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ContractImportValidator(ChargeTypeRepository chargeTypes, AccountRepository accounts,
                                   PropertyRepository properties, RenterRepository renters,
                                   UnitRepository units, LeaseRepository leases,
                                   ImportBatchLeaseRepository batchLeases,
                                   ImportBatchEntityRepository batchEntities,
                                   ImportBatchRepository batchRepository,
                                   PropertyAccountService propertyAccounts) {
        this.chargeTypes = chargeTypes;
        this.accounts = accounts;
        this.properties = properties;
        this.renters = renters;
        this.units = units;
        this.leases = leases;
        this.batchLeases = batchLeases;
        this.batchEntities = batchEntities;
        this.batchRepository = batchRepository;
        this.propertyAccounts = propertyAccounts;
    }

    /**
     * For the sheet rules on their own, with the database supplied by the caller.
     * Only {@link #validate(Workbook, Lookups)} is reachable from an instance built
     * this way; {@link #validate(Workbook)} would dereference null repositories.
     */
    ContractImportValidator() {
        this(null, null, null, null, null, null, null, null, null, null);
    }

    // ------------------------------------------------------------------
    // what the database is asked
    // ------------------------------------------------------------------

    /** A leaf the chart holds, or the fact that two leaves answer to one name. */
    public record AccountMatch(UUID id, String name, boolean group, boolean active,
                               AccountType type, boolean ambiguous) {}

    /** As much of a charge type as a line's rules need. */
    public record ChargeTypeRef(String code, ChargeBehaviour behaviour, AccountRole role) {}

    /**
     * Every question this pass asks outside the workbook.
     *
     * <p>An interface rather than seven injected repositories used directly, for two
     * reasons that are both about correctness rather than testing convenience: it
     * puts every database access of the pass in one place that is provably inside
     * the read-only transaction, and it lets the sheet rules — which are the bulk of
     * this class and the part that actually goes wrong — be exercised without a
     * Spring context or a Postgres container.</p>
     */
    public interface Lookups {
        /** The charge type this code names, or null. Case-insensitive (review R8). */
        ChargeTypeRef chargeType(String code);

        /** The leaf this name names, or null when the chart has none. */
        AccountMatch account(String name);

        /** The account type the tenant's template says this role must be, or null when it says nothing. */
        AccountType expectedTypeForRole(AccountRole role);

        /**
         * Where this organisation's existing property of that name came from,
         * phrased for an error message — or null when it has none.
         *
         * <p>A sentence rather than a boolean because "it already exists" is not
         * an instruction. A cut-over that has been reversed leaves its properties
         * standing by design, and the accountant reloading a corrected workbook
         * needs to be told which batch to discard, not merely that something is in
         * the way.</p>
         */
        String existingProperty(String nameEn);

        /** The same, for a renter's email. */
        String existingRenter(String email);

        /** Who is already living there, phrased for an error message — or null when nobody is. */
        String liveLeaseOn(String propertyName, String buildingName, String unitNumber);

        /**
         * Where this organisation already holds a lease under that contract
         * reference, phrased for an error message — or null when it does not.
         *
         * <p>A second lease under one reference is the cross-workbook half of the
         * silent merge: nothing downstream can tell which of the two a payment,
         * a renewal or an auditor's query means.</p>
         */
        String externalRefUsedBy(String contractNumber);
    }

    // ------------------------------------------------------------------
    // entry points
    // ------------------------------------------------------------------

    /**
     * The Contracts sheet is the discriminator between a v1 portfolio workbook and a
     * v2 cut-over one: no other v2-only sheet is mandatory, and the v1 template has
     * never had one.
     */
    public static boolean isV2Workbook(Workbook wb) {
        return wb != null && wb.getSheet("Contracts") != null;
    }

    /** The production pass. See the class note on why this is transactional. */
    @Transactional(readOnly = true)
    public PortfolioImportService.ValidationOutcome validate(Workbook wb) {
        return validate(wb, new RepositoryLookups());
    }

    public PortfolioImportService.ValidationOutcome validate(Workbook wb, Lookups lk) {
        List<ImportErrorDTO> errors = new ArrayList<>();
        List<ImportErrorDTO> warnings = new ArrayList<>();

        Sheet propertiesSheet = wb.getSheet("Properties");
        Sheet unitsSheet = wb.getSheet("Units");
        Sheet rentersSheet = wb.getSheet("Renters");
        Sheet contractsSheet = wb.getSheet("Contracts");
        Sheet chequesSheet = wb.getSheet("Cheques");

        if (propertiesSheet == null) errors.add(missingSheet("Properties"));
        if (unitsSheet == null) errors.add(missingSheet("Units"));
        if (rentersSheet == null) errors.add(missingSheet("Renters"));
        if (contractsSheet == null) errors.add(missingSheet("Contracts"));
        // Nothing further can be said about a workbook that is missing a sheet, and
        // saying it anyway would bury the one error that matters under a hundred
        // "not found on the Properties sheet".
        if (!errors.isEmpty()) return new PortfolioImportService.ValidationOutcome(errors, warnings);

        Set<String> propertyNames = validateProperties(propertiesSheet, lk, errors, warnings);
        Set<String> unitKeys = validateUnits(unitsSheet, propertyNames, errors);
        Set<String> renterEmails = validateRenters(rentersSheet, lk, errors);
        Set<String> brokenContracts = new LinkedHashSet<>();
        Map<String, ContractSummary> contracts =
                validateContracts(contractsSheet, propertyNames, unitKeys, renterEmails, lk, errors,
                        brokenContracts);
        validateCheques(chequesSheet, contracts, brokenContracts, lk, errors, warnings);

        return new PortfolioImportService.ValidationOutcome(errors, warnings);
    }

    private static ImportErrorDTO missingSheet(String name) {
        return ImportErrorDTO.file(name, "Sheet", "Sheet '" + name + "' is missing");
    }

    // ------------------------------------------------------------------
    // Properties — the six account columns are the cut-over's own addition
    // ------------------------------------------------------------------

    private Set<String> validateProperties(Sheet sheet, Lookups lk,
                                           List<ImportErrorDTO> errors, List<ImportErrorDTO> warnings) {
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);
        Set<String> names = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String name = SheetCells.getCellString(row, 0);
            if (name.isEmpty()) {
                errors.add(new ImportErrorDTO("Properties", rowNum, "PropertyName", "PropertyName is required"));
            } else if (!names.add(name.toLowerCase(Locale.ROOT))) {
                // Not a merge, on purpose (review R10): PropertyAccountService
                // resolves a property's leaves by NAME, so two properties sharing one
                // would share one set of ledgers and a whole tower's rent would land
                // in another tower's income account.
                errors.add(new ImportErrorDTO("Properties", rowNum, "PropertyName",
                        "Property '" + name + "' is listed twice on this sheet"));
            } else {
                String existing = lk.existingProperty(name);
                if (existing != null) {
                    errors.add(new ImportErrorDTO("Properties", rowNum, "PropertyName",
                            "A property named '" + name + "' already exists in this organisation ("
                                    + existing + "). Discard that batch first, or correct its draft"
                                    + " leases instead of re-importing."));
                }
            }

            enumCell(SheetCells.getCellString(row, 2), Emirate.class, true,
                    "Properties", rowNum, "Emirate", errors);
            enumCell(SheetCells.getCellString(row, 4), PropertyType.class, true,
                    "Properties", rowNum, "Type", errors);

            for (Map.Entry<String, AccountRole> e : ACCOUNT_COLUMN_ROLES.entrySet()) {
                String accountName = SheetCells.cell(row, hi, e.getKey());
                if (accountName.isEmpty()) {
                    // Reported, never guessed (spec §10.3). The property may still
                    // get this leaf from the tenant's template at persist time, which
                    // is why it is a warning and not a refusal.
                    warnings.add(new ImportErrorDTO("Properties", rowNum, e.getKey(),
                            e.getValue() + " is not named for '" + name
                                    + "'; it will be taken from the account template if one covers it."));
                    continue;
                }
                resolveRoleAccount(accountName, e.getValue(), lk, "Properties", rowNum, e.getKey(), errors);
            }
        }
        return names;
    }

    /**
     * A named leaf fit for {@code expectedFor}, or null with the reason recorded.
     *
     * <p>Unknown and ambiguous are both errors naming the cell and the value, never a
     * guess: routing a tower's rent into the wrong ledger by picking one of two
     * same-named leaves is a mistake nobody finds until the year-end.</p>
     *
     * <p>The type check is not belt-and-braces. Every one of these names is handed
     * to a service that refuses the wrong type by <em>throwing</em> —
     * {@code PropertyAccountService.setMapping} for the six columns,
     * {@code LeaseService} for a line's credit account — and an exception out of a
     * nested transactional call halfway through a bulk import does not lose one
     * property, it loses the workbook. Refused here, it is a cell reference instead.</p>
     *
     * @param expectedType the account type this cell must name, or null when nothing constrains it.
     * @param expectedFor  what wants it, for the message — a role name.
     */
    private AccountMatch resolveAccount(String name, AccountType expectedType, String expectedFor, Lookups lk,
                                        String sheet, int rowNum, String field, List<ImportErrorDTO> errors) {
        AccountMatch match = lk.account(name);
        if (match == null) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    "No ledger account is named '" + name + "' in this organisation's chart."));
            return null;
        }
        if (match.ambiguous()) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    "More than one ledger account is named '" + name
                            + "'; rename one of them so the sheet can say which is meant."));
            return null;
        }
        if (match.group()) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    "'" + name + "' is a group account; a role must name a leaf that can hold a balance."));
            return null;
        }
        if (!match.active()) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    "'" + name + "' is an inactive account; re-activate it or name another leaf."));
            return null;
        }
        if (expectedType != null && expectedType != match.type()) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    expectedFor + " expects an " + expectedType + " account; '" + name + "' is "
                            + match.type() + "."));
            return null;
        }
        return match;
    }

    private AccountMatch resolveRoleAccount(String name, AccountRole role, Lookups lk,
                                            String sheet, int rowNum, String field,
                                            List<ImportErrorDTO> errors) {
        return resolveAccount(name, lk.expectedTypeForRole(role), role.name(), lk, sheet, rowNum, field, errors);
    }

    // ------------------------------------------------------------------
    // Units
    // ------------------------------------------------------------------

    private Set<String> validateUnits(Sheet sheet, Set<String> propertyNames, List<ImportErrorDTO> errors) {
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String propertyName = SheetCells.getCellString(row, 0);
            String buildingName = SheetCells.getCellString(row, 1);
            String unitNumber = SheetCells.getCellString(row, 2);

            if (propertyName.isEmpty()) {
                errors.add(new ImportErrorDTO("Units", rowNum, "PropertyName", "PropertyName is required"));
            } else if (!propertyNames.contains(propertyName.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Units", rowNum, "PropertyName",
                        "Property '" + propertyName + "' is not on the Properties sheet"));
            }
            if (unitNumber.isEmpty()) {
                errors.add(new ImportErrorDTO("Units", rowNum, "UnitNumber", "UnitNumber is required"));
            } else if (!keys.add(unitKey(propertyName, buildingName, unitNumber))) {
                errors.add(new ImportErrorDTO("Units", rowNum, "UnitNumber",
                        "Unit '" + unitNumber + "' of '" + propertyName + "' is listed twice on this sheet"));
            }
            enumCell(SheetCells.getCellString(row, 3), UnitType.class, false,
                    "Units", rowNum, "UnitType", errors);
            numericCell(SheetCells.getCellString(row, 4), "Units", rowNum, "SizeSqft", errors);
            numericCell(SheetCells.getCellString(row, 5), "Units", rowNum, "ExpectedRent", errors);
        }
        return keys;
    }

    static String unitKey(String propertyName, String buildingName, String unitNumber) {
        return (propertyName.trim() + "|" + buildingName.trim() + "|" + unitNumber.trim())
                .toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Renters
    // ------------------------------------------------------------------

    private Set<String> validateRenters(Sheet sheet, Lookups lk, List<ImportErrorDTO> errors) {
        Set<String> emails = new LinkedHashSet<>();
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            if (SheetCells.getCellString(row, 0).isEmpty()) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Name", "Name is required"));
            }
            String email = SheetCells.getCellString(row, 2);
            if (email.isEmpty()) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Email is required"));
            } else if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Invalid email format: " + email));
            } else if (!emails.add(email.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Renters", rowNum, "Email", "Duplicate email: " + email));
            } else {
                // Same reason as the property rule: a second renter row for a person
                // the organisation already has would split their history in two.
                String existing = lk.existingRenter(email);
                if (existing != null) {
                    errors.add(new ImportErrorDTO("Renters", rowNum, "Email",
                            "A renter with email '" + email + "' already exists in this organisation ("
                                    + existing + "). Discard that batch first, or correct its draft"
                                    + " leases instead of re-importing."));
                }
            }
        }
        return emails;
    }

    // ------------------------------------------------------------------
    // Contracts — one row per LINE; the header comes from a contract's first row
    // ------------------------------------------------------------------

    /**
     * A contract as the cheque rules need to see it.
     *
     * @param grossTotal Σ (line net + the line's own VAT) — the figure
     *                   {@code LeasePostingService} compares the grid against at post
     *                   (review R5), not the net the brief measured.
     */
    record ContractSummary(String number, String propertyName, String buildingName, String unitNumber,
                           BigDecimal grossTotal, int firstRowNum) {}

    /**
     * The columns that describe the CONTRACT rather than the line.
     *
     * <p>They are read from a contract's first row and every later row of that
     * contract must either repeat them exactly or leave them blank. Before this
     * rule, a later row's header cells were simply skipped — so two genuinely
     * different contracts typed with one number silently became ONE lease, on the
     * first one's unit, carrying both contracts' lines. The second renter was never
     * leased, their unit was never claimed, and the cheque-total guard passed
     * because both sides accumulated under the same key. It is the same "never a
     * silent merge" rule the properties and renters already had (review C1/R10),
     * applied to the one place that still merged.</p>
     */
    static final List<String> HEADER_COLUMNS = List.of(
            "EjariNumber", "PropertyName", "BuildingName", "UnitNumber", "RenterEmail",
            "ContractDate", "StartDate", "EndDate", "GracePeriodDays");

    private Map<String, ContractSummary> validateContracts(Sheet sheet, Set<String> propertyNames,
                                                           Set<String> unitKeys, Set<String> renterEmails,
                                                           Lookups lk, List<ImportErrorDTO> errors,
                                                           Set<String> brokenContracts) {
        SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);
        Map<String, ContractSummary> byNumber = new LinkedHashMap<>();
        Map<String, BigDecimal> grossByNumber = new LinkedHashMap<>();
        Map<String, Set<Integer>> lineNos = new HashMap<>();
        /** unit key -> the first contract that claimed it. */
        Map<String, String> claimedUnits = new HashMap<>();
        /** contract -> the header cells its first row carried, for later rows to match. */
        Map<String, Map<String, String>> headerByNumber = new LinkedHashMap<>();
        /**
         * Contracts whose shape is already wrong. Their line totals are unreliable,
         * so the Sigma check is suppressed for them rather than fired as a second,
         * misleading error about money (review M7).
         */
        Set<String> structurallyBroken = new LinkedHashSet<>();

        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null || SheetCells.isRowEmpty(row)) continue;
            int rowNum = i + 1;

            String number = SheetCells.cell(row, hi, "ContractNumber");
            if (number.isEmpty()) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ContractNumber",
                        "ContractNumber is required on every line of a contract"));
                continue;
            }
            if (number.length() > MAX_CONTRACT_REF) {
                // Recorded, not skipped: skipping the row used to leave the
                // contract's total short and fire a second, misleading cheque
                // mismatch about money on a row whose real problem is its name.
                if (structurallyBroken.add(number)) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "ContractNumber",
                            "ContractNumber is longer than " + MAX_CONTRACT_REF + " characters"));
                }
            }

            // ---- the line, which every row carries ----
            int lineNo = positiveInt(SheetCells.cell(row, hi, "LineNo"), "Contracts", rowNum, "LineNo", errors);
            if (lineNo > 0 && !lineNos.computeIfAbsent(number, k -> new HashSet<>()).add(lineNo)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "LineNo",
                        "Duplicate LineNo " + lineNo + " for contract " + number));
            }

            String code = SheetCells.cell(row, hi, "ChargeTypeCode");
            ChargeTypeRef chargeType = null;
            if (code.isEmpty()) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ChargeTypeCode", "ChargeTypeCode is required"));
            } else {
                chargeType = lk.chargeType(code);
                if (chargeType == null) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "ChargeTypeCode",
                            "No charge type with code '" + code
                                    + "'. Add it under Settings → Charge types, or use one of the seeded codes."));
                }
            }

            BigDecimal gross = amountOrNull(SheetCells.cell(row, hi, "GrossAmount"));
            String discountRaw = SheetCells.cell(row, hi, "DiscountAmount");
            BigDecimal discount = discountRaw.isBlank() ? BigDecimal.ZERO : amountOrNull(discountRaw);
            Boolean vatApplicable = boolOrNull(SheetCells.cell(row, hi, "VatApplicable"));

            if (gross == null || gross.signum() <= 0) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "GrossAmount",
                        "GrossAmount must be a number greater than zero"));
                gross = null;
            }
            if (discount == null) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "DiscountAmount",
                        "DiscountAmount must be a number"));
            } else if (gross != null && (discount.signum() < 0 || discount.compareTo(gross) > 0)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "DiscountAmount",
                        "DiscountAmount must be between 0 and GrossAmount"));
                discount = null;
            }
            if (vatApplicable == null) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "VatApplicable",
                        "VatApplicable must be true, false or blank"));
            }

            String creditAccount = SheetCells.cell(row, hi, "CreditAccount");
            if (!creditAccount.isEmpty() && chargeType != null) {
                // The bar an override has to clear is the charge type's own, and
                // ChargeTypeService owns it: a role outside the creditable allow-list
                // cannot be named by a line at all, and one inside it fixes the
                // account type. LeaseService applies exactly this when the line is
                // written — by throwing, which in a bulk import means losing the
                // workbook, so it is asked here first.
                AccountType expected = ChargeTypeService.expectedTypeFor(chargeType.role());
                if (expected == null) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "CreditAccount",
                            "Charge type " + chargeType.code() + " credits " + chargeType.role()
                                    + ", which a lease line cannot name an account for"));
                } else {
                    resolveAccount(creditAccount, expected, chargeType.role().name(), lk,
                            "Contracts", rowNum, "CreditAccount", errors);
                }
            } else if (!creditAccount.isEmpty()) {
                // The charge type is already an error on this row; resolving the
                // account against a role we do not know would add a second, wronger one.
                resolveAccount(creditAccount, null, "", lk, "Contracts", rowNum, "CreditAccount", errors);
            }

            if (gross != null && discount != null && vatApplicable != null) {
                BigDecimal net = gross.subtract(discount);
                BigDecimal vat = LeaseVat.vatOfNet(net, vatApplicable,
                        chargeType == null ? null : chargeType.behaviour());
                grossByNumber.merge(number, net.add(vat), BigDecimal::add);
            }

            // ---- the header, read from the contract's FIRST row only ----
            if (byNumber.containsKey(number)) {
                // ...but a later row may not CONTRADICT it. See HEADER_COLUMNS.
                Map<String, String> first = headerByNumber.get(number);
                int firstRowNum = byNumber.get(number).firstRowNum();
                for (String column : HEADER_COLUMNS) {
                    String here = SheetCells.cell(row, hi, column).trim();
                    if (here.isEmpty()) continue;           // a blank continuation row is the normal shape
                    String there = first.getOrDefault(column, "");
                    if (here.equalsIgnoreCase(there)) continue;
                    structurallyBroken.add(number);
                    errors.add(new ImportErrorDTO("Contracts", rowNum, column,
                            "ContractNumber " + number + " is already used by the contract on row "
                                    + firstRowNum + ", whose " + column + " is '" + there + "' — not '"
                                    + here + "'. A second contract needs its own number."));
                }
                continue;
            }

            String propertyName = SheetCells.cell(row, hi, "PropertyName");
            String buildingName = SheetCells.cell(row, hi, "BuildingName");
            String unitNumber = SheetCells.cell(row, hi, "UnitNumber");
            String renterEmail = SheetCells.cell(row, hi, "RenterEmail");

            if (!propertyNames.contains(propertyName.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "PropertyName",
                        "Property '" + propertyName + "' is not on the Properties sheet"));
            }
            String key = unitKey(propertyName, buildingName, unitNumber);
            if (!unitKeys.contains(key)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "UnitNumber",
                        "Unit '" + unitNumber + "' in building '" + buildingName + "' of property '"
                                + propertyName + "' is not on the Units sheet"));
            } else {
                // One live tenancy per unit, asked twice: of the workbook, and of the
                // organisation. Both would be two people holding one flat the moment
                // the batch posts, and ux_leases_one_active_per_unit would refuse the
                // second one halfway through the run rather than here.
                String claimedBy = claimedUnits.putIfAbsent(key, number);
                if (claimedBy != null) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "UnitNumber",
                            "Unit '" + unitNumber + "' of '" + propertyName + "' is already let by contract "
                                    + claimedBy + " on this sheet"));
                }
                String occupant = lk.liveLeaseOn(propertyName, buildingName, unitNumber);
                if (occupant != null) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "UnitNumber",
                            "Unit '" + unitNumber + "' of '" + propertyName + "' is " + occupant));
                }
            }
            if (!renterEmails.contains(renterEmail.toLowerCase(Locale.ROOT))) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "RenterEmail",
                        "Renter email '" + renterEmail + "' is not on the Renters sheet"));
            }

            dateCell(SheetCells.cell(row, hi, "ContractDate"), true, "Contracts", rowNum, "ContractDate", errors);
            LocalDate startDate = dateCell(SheetCells.cell(row, hi, "StartDate"), true,
                    "Contracts", rowNum, "StartDate", errors);
            LocalDate endDate = dateCell(SheetCells.cell(row, hi, "EndDate"), true,
                    "Contracts", rowNum, "EndDate", errors);
            if (startDate != null && endDate != null && !endDate.isAfter(startDate)) {
                errors.add(new ImportErrorDTO("Contracts", rowNum, "EndDate",
                        "EndDate " + endDate + " must be after StartDate " + startDate));
            }

            String grace = SheetCells.cell(row, hi, "GracePeriodDays");
            if (!grace.isEmpty()) {
                try {
                    if (Integer.parseInt(grace.trim()) < 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    errors.add(new ImportErrorDTO("Contracts", rowNum, "GracePeriodDays",
                            "GracePeriodDays must be a whole number of days, or blank"));
                }
            }

            String usedBy = lk.externalRefUsedBy(number);
            if (usedBy != null) {
                structurallyBroken.add(number);
                errors.add(new ImportErrorDTO("Contracts", rowNum, "ContractNumber",
                        "This organisation already has a lease under contract " + number
                                + " (" + usedBy + "). Discard that batch first, or give this contract"
                                + " its own number."));
            }

            Map<String, String> header = new LinkedHashMap<>();
            for (String column : HEADER_COLUMNS) {
                header.put(column, SheetCells.cell(row, hi, column).trim());
            }
            headerByNumber.put(number, header);
            byNumber.put(number,
                    new ContractSummary(number, propertyName, buildingName, unitNumber, BigDecimal.ZERO, rowNum));
        }

        // Fold the accumulated gross totals onto the summaries now that every line
        // of every contract has been seen.
        Map<String, ContractSummary> out = new LinkedHashMap<>();
        byNumber.forEach((number, s) -> out.put(number, new ContractSummary(s.number(), s.propertyName(),
                s.buildingName(), s.unitNumber(),
                grossByNumber.getOrDefault(number, BigDecimal.ZERO), s.firstRowNum())));
        brokenContracts.addAll(structurallyBroken);
        return out;
    }

    // ------------------------------------------------------------------
    // Cheques
    // ------------------------------------------------------------------

    private void validateCheques(Sheet sheet, Map<String, ContractSummary> contracts,
                                 Set<String> brokenContracts, Lookups lk,
                                 List<ImportErrorDTO> errors, List<ImportErrorDTO> warnings) {
        Map<String, BigDecimal> sumByContract = new LinkedHashMap<>();
        Set<String> withCheques = new LinkedHashSet<>();

        if (sheet != null) {
            SheetCells.HeaderIndex hi = new SheetCells.HeaderIndex(sheet);
            Map<String, Set<Integer>> seqByContract = new HashMap<>();
            Map<String, Set<String>> numbersByContract = new HashMap<>();

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || SheetCells.isRowEmpty(row)) continue;
                int rowNum = i + 1;

                String number = SheetCells.cell(row, hi, "ContractNumber");
                if (!contracts.containsKey(number)) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "ContractNumber",
                            "No Contracts row with ContractNumber '" + number + "'"));
                    continue;
                }
                withCheques.add(number);

                int seq = positiveInt(SheetCells.cell(row, hi, "SeqNo"), "Cheques", rowNum, "SeqNo", errors);
                if (seq > 0 && !seqByContract.computeIfAbsent(number, k -> new HashSet<>()).add(seq)) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "SeqNo",
                            "Duplicate SeqNo " + seq + " for contract " + number));
                }

                ChequeMode mode = validateMode(row, hi, rowNum, errors);
                validateChequeNumber(row, hi, mode, number, numbersByContract, rowNum, errors);

                dateCell(SheetCells.cell(row, hi, "PostingDate"), true, "Cheques", rowNum, "PostingDate", errors);
                LocalDate chequeDate = dateCell(SheetCells.cell(row, hi, "ChequeDate"), true,
                        "Cheques", rowNum, "ChequeDate", errors);
                validateStatusAndDates(row, hi, mode, chequeDate, rowNum, errors);

                String debitAccount = SheetCells.cell(row, hi, "DebitAccount");
                if (!debitAccount.isEmpty()) {
                    resolveRoleAccount(debitAccount, AccountRole.BANK, lk, "Cheques", rowNum, "DebitAccount", errors);
                }

                BigDecimal amount = amountOrNull(SheetCells.cell(row, hi, "Amount"));
                if (amount == null || amount.signum() <= 0) {
                    errors.add(new ImportErrorDTO("Cheques", rowNum, "Amount",
                            "Amount must be a number greater than zero"));
                } else {
                    sumByContract.merge(number, amount, BigDecimal::add);
                }
            }
        }

        // Spec §6.4 rule 3, as LeasePostingService enforces it at post (review R5):
        // Σ cheques == Σ (line net + its VAT), to the fils. Both figures in the
        // message, because "they do not match" without them sends the accountant back
        // to the spreadsheet to work out which side is wrong.
        for (Map.Entry<String, ContractSummary> e : contracts.entrySet()) {
            ContractSummary c = e.getValue();
            // A contract whose shape is already wrong has an unreliable total; a
            // second error about money would send the accountant to the wrong cell.
            if (brokenContracts.contains(e.getKey())) continue;
            if (!withCheques.contains(e.getKey())) {
                warnings.add(new ImportErrorDTO("Cheques", c.firstRowNum(), "ContractNumber",
                        "Contract " + e.getKey() + " has no cheque rows; it will import but cannot be posted"));
                continue;
            }
            BigDecimal sum = sumByContract.getOrDefault(e.getKey(), BigDecimal.ZERO);
            if (sum.compareTo(c.grossTotal()) != 0) {
                errors.add(new ImportErrorDTO("Cheques", c.firstRowNum(), "Amount",
                        "Cheques for " + e.getKey() + " total " + money(sum)
                                + " but the contract's lines come to " + money(c.grossTotal())
                                + " including VAT. If the difference is a booking fee, a security"
                                + " deposit or an admin charge, add it as a line on the Contracts"
                                + " sheet — every instrument a contract collects is one of its lines."));
            }
        }
    }

    private ChequeMode validateMode(Row row, SheetCells.HeaderIndex hi, int rowNum, List<ImportErrorDTO> errors) {
        String raw = SheetCells.cell(row, hi, "Mode");
        if (raw.isEmpty()) return ChequeMode.PDC;
        Optional<ChequeMode> mode = Arrays.stream(ChequeMode.values())
                .filter(m -> m.name().equalsIgnoreCase(raw.trim()))
                .findFirst();
        if (mode.isEmpty()) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Mode",
                    "Mode must be one of PDC, CASH, TRANSFER"));
            return null;
        }
        if (mode.get() == ChequeMode.ONLINE) {
            // ChequeRowRules refuses this on every other door too: an online receipt
            // is written by the payment gateway with its own reference, so one typed
            // into a cut-over sheet would be a receipt with no payment behind it.
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Mode",
                    "ONLINE receipts are recorded by the payment gateway, not imported"));
            return null;
        }
        return mode.get();
    }

    private void validateChequeNumber(Row row, SheetCells.HeaderIndex hi, ChequeMode mode, String contract,
                                      Map<String, Set<String>> numbersByContract, int rowNum,
                                      List<ImportErrorDTO> errors) {
        String chequeNumber = SheetCells.cell(row, hi, "ChequeNumber");
        if (mode == null) return;
        if (mode == ChequeMode.PDC) {
            if (chequeNumber.isEmpty()) {
                errors.add(new ImportErrorDTO("Cheques", rowNum, "ChequeNumber",
                        "A post-dated cheque needs the number printed on it"));
            } else if (!numbersByContract.computeIfAbsent(contract, k -> new HashSet<>()).add(chequeNumber)) {
                // Per contract, which is what ux_cheques_lease_number enforces — two
                // leases may legitimately hold a cheque numbered 000101.
                errors.add(new ImportErrorDTO("Cheques", rowNum, "ChequeNumber",
                        "Cheque number " + chequeNumber + " is used twice on contract " + contract));
            }
        } else if (!chequeNumber.isEmpty()) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "ChequeNumber",
                    "A " + mode + " receipt has no cheque number; put its reference in Narration"));
        }
    }

    /**
     * The status the row is to become at bulk post, and the dates that make it
     * replayable.
     *
     * <p>The dates are not decoration. Bulk post replays each row through
     * {@code ChequeService}, which walks DRAFT → REGISTERED → DEPOSITED → CLEARED,
     * and each transition writes a journal <em>dated</em>. A CLEARED row with no
     * cleared date would post its CRT under today's date, which is the one date it
     * certainly did not clear on; a cleared post-dated cheque with no deposited date
     * cannot be banked at all, because {@code clear} refuses anything that is not
     * already DEPOSITED.</p>
     */
    private void validateStatusAndDates(Row row, SheetCells.HeaderIndex hi, ChequeMode mode,
                                        LocalDate chequeDate, int rowNum, List<ImportErrorDTO> errors) {
        String raw = SheetCells.cell(row, hi, "Status");
        String status = raw.isEmpty() ? "REGISTERED" : raw.trim().toUpperCase(Locale.ROOT);
        boolean known = IMPORTABLE_STATUSES.contains(status);
        if (!known) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Status",
                    "Status must be one of REGISTERED, DEPOSITED, CLEARED, BOUNCED"
                            + " — closed history stays in PACT (spec §14)"));
        }

        boolean banked = mode == ChequeMode.PDC
                && ("DEPOSITED".equals(status) || "CLEARED".equals(status) || "BOUNCED".equals(status));
        if (known && mode != null && mode != ChequeMode.PDC
                && ("DEPOSITED".equals(status) || "BOUNCED".equals(status))) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "Status",
                    "A " + mode + " receipt is never banked, so it cannot be " + status
                            + "; a receipt that arrived is CLEARED"));
            return;
        }

        // DepositedDate is required only where nothing can stand in for it — a row
        // that got no further than the bank. See depositedOnFor: PACT's exports
        // record a cheque's realisation, not the day it was handed to the bank, so
        // demanding one would make most of a real cut-over unimportable.
        boolean depositDateRequired = banked && "DEPOSITED".equals(status);
        LocalDate deposited = dateCell(SheetCells.cell(row, hi, "DepositedDate"), depositDateRequired,
                "Cheques", rowNum, "DepositedDate", errors);
        LocalDate cleared = dateCell(SheetCells.cell(row, hi, "ClearedDate"), "CLEARED".equals(status),
                "Cheques", rowNum, "ClearedDate", errors);
        LocalDate bounced = dateCell(SheetCells.cell(row, hi, "BouncedDate"), "BOUNCED".equals(status),
                "Cheques", rowNum, "BouncedDate", errors);

        if (!known) return;

        // A date the status does not account for is a row somebody edited halfway:
        // the replay would ignore it, and the register would then disagree with the
        // spreadsheet it came from about what happened to the money.
        if (!banked && deposited != null) {
            errors.add(outOfPlace(rowNum, "DepositedDate", status));
        }
        if (!"CLEARED".equals(status) && !"BOUNCED".equals(status) && cleared != null) {
            errors.add(outOfPlace(rowNum, "ClearedDate", status));
        }
        if (!"BOUNCED".equals(status) && bounced != null) {
            errors.add(outOfPlace(rowNum, "BouncedDate", status));
        }

        // The EFFECTIVE deposit date, so a row that leaves DepositedDate blank is
        // checked against the same ordering ck_cheques_imported_dates will apply to
        // the value this import is about to write for it.
        LocalDate bankedOn = mode == null ? deposited
                : depositedOnFor(mode, ChequeStatus.valueOf(status), deposited, cleared, bounced);

        if (chequeDate != null && bankedOn != null && bankedOn.isBefore(chequeDate)) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "DepositedDate",
                    (deposited != null ? "DepositedDate " : "The date this cheque was banked, ")
                            + bankedOn + ", is before ChequeDate " + chequeDate));
        }
        if (bankedOn != null && cleared != null && cleared.isBefore(bankedOn)) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "ClearedDate",
                    "ClearedDate " + cleared + " is before DepositedDate " + bankedOn));
        }
        LocalDate before = cleared != null ? cleared : bankedOn;
        if (before != null && bounced != null && bounced.isBefore(before)) {
            errors.add(new ImportErrorDTO("Cheques", rowNum, "BouncedDate",
                    "BouncedDate " + bounced + " is before " + (cleared != null ? "ClearedDate" : "DepositedDate")
                            + " " + before));
        }
    }

    /**
     * The day an imported cheque went to the bank — the sheet's own
     * {@code DepositedDate}, or the best available stand-in.
     *
     * <p>The stand-in exists because of what the client's PACT exports actually
     * contain. Their General Ledger records a post-dated receipt and then the
     * realisation (a {@code CRT}) or the return (a {@code CBR}); the day the paper
     * was physically handed to the bank is not a ledger event and is not exported.
     * Requiring it would make most of a real cut-over unimportable over a date
     * nobody has, so a row that says only "this cleared on the 25th" is taken to
     * have been banked on the 25th.</p>
     *
     * <p>That is a stated approximation, not a guess at unknown truth: it is the
     * latest day the cheque can have been deposited, the resulting {@code PDR} →
     * {@code CRT} pair nets to the same position, and the bank's own date — the one
     * that matters to the ledger — is the real one throughout.</p>
     *
     * <p>Public and shared: the validator decides whether a row is acceptable with
     * it, and {@code ContractImportPersistService} writes the column with it. Two
     * copies would mean a workbook that validates and then writes a date the
     * database constraint refuses.</p>
     */
    public static LocalDate depositedOnFor(ChequeMode mode, ChequeStatus status, LocalDate deposited,
                                           LocalDate cleared, LocalDate bounced) {
        if (mode != ChequeMode.PDC || status == null) return null;
        return switch (status) {
            case DEPOSITED -> deposited;
            case CLEARED -> deposited != null ? deposited : cleared;
            case BOUNCED -> deposited != null ? deposited : (cleared != null ? cleared : bounced);
            default -> null;
        };
    }

    private static ImportErrorDTO outOfPlace(int rowNum, String field, String status) {
        return new ImportErrorDTO("Cheques", rowNum, field,
                field + " is set on a row whose Status is " + status + "; clear one or the other");
    }

    // ------------------------------------------------------------------
    // cell primitives
    // ------------------------------------------------------------------

    private static <E extends Enum<E>> void enumCell(String raw, Class<E> type, boolean required,
                                                     String sheet, int rowNum, String field,
                                                     List<ImportErrorDTO> errors) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " is required"));
            return;
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "_");
        boolean ok = Arrays.stream(type.getEnumConstants()).anyMatch(c -> c.name().equals(normalised));
        if (!ok) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field, "Invalid " + field + ": " + raw + ". Valid: "
                    + Arrays.toString(type.getEnumConstants())));
        }
    }

    private static void numericCell(String raw, String sheet, int rowNum, String field,
                                    List<ImportErrorDTO> errors) {
        if (raw == null || raw.isBlank()) return;
        if (amountOrNull(raw) == null) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " must be a number"));
        }
    }

    /** 0 when the value is not a positive integer; the error is already recorded. */
    private static int positiveInt(String raw, String sheet, int rowNum, String field,
                                   List<ImportErrorDTO> errors) {
        try {
            int n = Integer.parseInt(raw.trim());
            if (n < 1) throw new NumberFormatException();
            return n;
        } catch (RuntimeException e) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " must be a positive whole number"));
            return 0;
        }
    }

    static LocalDate dateCell(String raw, boolean required, String sheet, int rowNum, String field,
                              List<ImportErrorDTO> errors) {
        if (raw == null || raw.isBlank()) {
            if (required) errors.add(new ImportErrorDTO(sheet, rowNum, field, field + " is required"));
            return null;
        }
        LocalDate d = SheetCells.parseDateOrNull(raw);
        if (d == null) {
            errors.add(new ImportErrorDTO(sheet, rowNum, field,
                    field + " '" + raw.trim() + "' is not a date; use YYYY-MM-DD"));
        }
        return d;
    }

    static BigDecimal amountOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** null means "not a boolean"; blank is false, as an unticked column is. */
    static Boolean boolOrNull(String raw) {
        if (raw == null || raw.isBlank()) return Boolean.FALSE;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> Boolean.TRUE;
            case "false", "no", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    // ------------------------------------------------------------------
    // the production Lookups
    // ------------------------------------------------------------------

    /**
     * The only place this pass touches the database, built inside
     * {@link #validate(Workbook)}'s read-only transaction.
     *
     * <p>The chart is read once and indexed by name because the same six names
     * recur on every property row; the existence checks are one narrow query per
     * name instead, because a cut-over organisation is normally empty and loading
     * every renter to answer "does this one exist" would be the wrong trade at the
     * one size that matters. Every query is scoped to the caller's tenant
     * explicitly, not only by the Hibernate filter, since the whole point of this
     * class running on the executor thread is that assuming the filter is on has
     * leaked data here before.</p>
     */
    private final class RepositoryLookups implements Lookups {

        private final UUID tenantId = requireTenant();
        private final Map<String, AccountMatch> chart = loadChart();

        private UUID requireTenant() {
            UUID id = TenantContextHolder.getTenantId();
            if (id == null) {
                throw new IllegalStateException(
                        "Contract import validation ran without a tenant context; refusing to run unscoped queries");
            }
            return id;
        }

        private Map<String, AccountMatch> loadChart() {
            Map<String, AccountMatch> byName = new HashMap<>();
            for (Account a : accounts.findAll()) {
                if (a.getName() == null) continue;
                String key = a.getName().trim().toLowerCase(Locale.ROOT);
                AccountMatch seen = byName.get(key);
                byName.put(key, seen == null
                        ? new AccountMatch(a.getId(), a.getName(), a.isGroup(), a.isActive(), a.getAccountType(), false)
                        : new AccountMatch(seen.id(), seen.name(), seen.group(), seen.active(), seen.type(), true));
            }
            return byName;
        }

        @Override public ChargeTypeRef chargeType(String code) {
            Optional<ChargeType> found = chargeTypes.findByCodeIgnoreCase(code.trim());
            if (found.isPresent()) {
                ChargeType c = found.get();
                return new ChargeTypeRef(c.getCode(), c.getBehaviour(), c.getRole());
            }
            // The persist phase seeds the catalogue before it builds a line, so a
            // seeded code that this tenant has not got yet is not an error.
            ChargeTypeDTO seeded = ChargeTypeService.seededDefault(code);
            return seeded == null ? null : new ChargeTypeRef(seeded.code(), seeded.behaviour(), seeded.role());
        }

        @Override public AccountMatch account(String name) {
            return chart.get(name.trim().toLowerCase(Locale.ROOT));
        }

        @Override public AccountType expectedTypeForRole(AccountRole role) {
            return propertyAccounts.expectedAccountTypeFor(role);
        }

        @Override public String existingProperty(String nameEn) {
            List<Property> held = properties.findByTenantIdAndNameEnIn(tenantId, List.of(nameEn));
            if (held.isEmpty()) return null;
            return madeBy(ImportedEntityType.PROPERTY, held.get(0).getId(), "created outside any import");
        }

        @Override public String existingRenter(String email) {
            var held = renters.findByTenantIdAndEmailIn(tenantId, List.of(email));
            if (held.isEmpty()) return null;
            return madeBy(ImportedEntityType.RENTER, held.get(0).getId(), "created outside any import");
        }

        /** Which batch made this row, phrased for the accountant, or {@code otherwise}. */
        private String madeBy(ImportedEntityType type, UUID entityId, String otherwise) {
            return batchEntities.findByEntityTypeAndEntityId(type, entityId).stream()
                    .map(link -> batchRepository.findById(link.getBatchId()).orElse(null))
                    .filter(b -> b != null && tenantId.equals(b.getTenantId()))
                    .findFirst()
                    .map(b -> "import batch '" + b.getLabel() + "', " + b.getStatus())
                    .orElse(otherwise);
        }

        @Override public String externalRefUsedBy(String contractNumber) {
            List<Lease> held = leases.findByTenantIdAndExternalContractRef(tenantId, contractNumber);
            if (held.isEmpty()) return null;
            Lease lease = held.get(0);
            String batch = batchLabelFor(lease.getId());
            return batch == null
                    ? "lease " + lease.getId() + ", " + lease.getStatus()
                    : batch;
        }

        /** Which import batch created this lease, phrased for the accountant, or null. */
        private String batchLabelFor(UUID leaseId) {
            return batchLeases.findByLeaseId(leaseId).stream()
                    .map(link -> batchRepository.findById(link.getBatchId()).orElse(null))
                    .filter(b -> b != null && tenantId.equals(b.getTenantId()))
                    .findFirst()
                    .map(b -> "import batch '" + b.getLabel() + "' (" + b.getStatus() + ")")
                    .orElse(null);
        }

        @Override public String liveLeaseOn(String propertyName, String buildingName, String unitNumber) {
            for (Property p : properties.findByTenantIdAndNameEnIn(tenantId, List.of(propertyName))) {
                for (Unit u : units.findByPropertyId(p.getId())) {
                    if (!unitNumber.trim().equalsIgnoreCase(u.getUnitNumber())) continue;
                    String existingBuilding = u.getBuilding() == null ? "" : u.getBuilding().getNameEn();
                    if (!buildingName.trim().equalsIgnoreCase(existingBuilding == null ? "" : existingBuilding)) {
                        continue;
                    }
                    for (Lease l : leases.findByUnitIdAndStatusIn(u.getId(), LeaseService.LIVE)) {
                        String who = l.getRenter() == null ? "another renter" : l.getRenter().getNameEn();
                        return "already let to " + who + " until " + l.getEndDate()
                                + " (lease " + l.getId() + ", " + l.getStatus() + ")";
                    }
                }
            }
            return null;
        }
    }
}
