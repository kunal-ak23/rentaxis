package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cut-over workbook's rules, with the database stubbed.
 *
 * <p>Everything the validator needs from the database arrives through
 * {@link ContractImportValidator.Lookups}, so the sheet rules can be exercised
 * without a Spring context — and the one production implementation of that
 * interface is the only place a repository is touched, which is also what keeps
 * the whole pass inside one read-only transaction (see the class Javadoc).</p>
 */
class ContractImportValidatorTest {

    // ------------------------------------------------------------------
    // the stub chart / catalogue
    // ------------------------------------------------------------------

    /** The six account names the sample property maps, plus the ones the lines name. */
    private final Map<String, ContractImportValidator.AccountMatch> chart = new LinkedHashMap<>();
    private final List<String> existingProperties = new ArrayList<>();
    private final List<String> existingRenterEmails = new ArrayList<>();
    private final Map<String, String> liveLeases = new LinkedHashMap<>();
    private final Map<String, String> existingContractRefs = new LinkedHashMap<>();

    ContractImportValidatorTest() {
        leaf("Rental Income ST1", AccountType.INCOME);
        leaf("Rent Receivable - ST1", AccountType.ASSET);
        leaf("Advance Rent - ST1", AccountType.LIABILITY);
        leaf("Sample Bank - ST1", AccountType.ASSET);
        leaf("PDC Receivable ST1", AccountType.ASSET);
        leaf("Security Deposit ST1", AccountType.LIABILITY);
        leaf("Admin Fee - ST1", AccountType.INCOME);
    }

    private void leaf(String name, AccountType type) {
        chart.put(name.toLowerCase(Locale.ROOT),
                new ContractImportValidator.AccountMatch(UUID.randomUUID(), name, false, true, type, false));
    }

    private ContractImportValidator.Lookups lookups() {
        return new ContractImportValidator.Lookups() {
            @Override public ContractImportValidator.ChargeTypeRef chargeType(String code) {
                return switch (code.toUpperCase(Locale.ROOT)) {
                    case "RENT" -> new ContractImportValidator.ChargeTypeRef(
                            "RENT", ChargeBehaviour.RENT, AccountRole.ADVANCE_RENT);
                    case "SECURITY_DEPOSIT" -> new ContractImportValidator.ChargeTypeRef(
                            "SECURITY_DEPOSIT", ChargeBehaviour.DEPOSIT, AccountRole.SECURITY_DEPOSIT);
                    case "ADMIN_FEE" -> new ContractImportValidator.ChargeTypeRef(
                            "ADMIN_FEE", ChargeBehaviour.FEE, AccountRole.ADMIN_FEE);
                    default -> null;
                };
            }

            @Override public ContractImportValidator.AccountMatch account(String name) {
                return chart.get(name.trim().toLowerCase(Locale.ROOT));
            }

            @Override public AccountType expectedTypeForRole(AccountRole role) {
                return switch (role) {
                    case RENTAL_INCOME, ADMIN_FEE -> AccountType.INCOME;
                    case RENT_RECEIVABLE, PDC_RECEIVABLE, BANK -> AccountType.ASSET;
                    case ADVANCE_RENT, SECURITY_DEPOSIT -> AccountType.LIABILITY;
                    default -> null;
                };
            }

            @Override public String existingProperty(String nameEn) {
                return existingProperties.stream().anyMatch(n -> n.equalsIgnoreCase(nameEn))
                        ? "import batch 'September cut-over', REVERSED" : null;
            }

            @Override public String existingRenter(String email) {
                return existingRenterEmails.stream().anyMatch(e -> e.equalsIgnoreCase(email))
                        ? "import batch 'September cut-over', REVERSED" : null;
            }

            @Override public String liveLeaseOn(String propertyName, String buildingName, String unitNumber) {
                return liveLeases.get((propertyName + "|" + buildingName + "|" + unitNumber)
                        .toLowerCase(Locale.ROOT));
            }

            @Override public String externalRefUsedBy(String contractNumber) {
                return existingContractRefs.get(contractNumber);
            }
        };
    }

    // ------------------------------------------------------------------
    // the fixture workbook
    // ------------------------------------------------------------------

    private static void row(Sheet s, int r, String... values) {
        Row row = s.createRow(r);
        for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
    }

    /**
     * Two contracts on one property. SAMPLE-0001 is VAT-free — 51,000 rent + 5,000
     * deposit against two cheques of 31,000 and 25,000. SAMPLE-0002 carries VAT on its
     * rent line, so its cheque is the GROSS 21,000 + 1,050 = 22,050 (review R5):
     * the deposit's own VatApplicable flag is ignored, because a deposit is never
     * taxed (LeaseVat).
     */
    private static Workbook workbook(boolean withContracts) {
        Workbook wb = new XSSFWorkbook();
        Sheet props = wb.createSheet("Properties");
        row(props, 0, "PropertyName", "PropertyNameAr", "Emirate", "Address", "Type", "MakaniNumber",
                "RentalIncomeAccount", "RentalReceivableAccount", "AdvanceRentAccount", "BankAccount",
                "PdcReceivableAccount", "SecurityDepositAccount");
        row(props, 1, "Sample Tower", "", "DUBAI", "", "RESIDENTIAL", "",
                "Rental Income ST1", "Rent Receivable - ST1", "Advance Rent - ST1",
                "Sample Bank - ST1", "PDC Receivable ST1", "Security Deposit ST1");

        Sheet units = wb.createSheet("Units");
        row(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        row(units, 1, "Sample Tower", "", "101", "BHK1", "", "");
        row(units, 2, "Sample Tower", "", "102", "BHK1", "", "");

        Sheet renters = wb.createSheet("Renters");
        row(renters, 0, "Name", "NameAr", "Email", "Phone");
        row(renters, 1, "Sample Renter One", "", "sample.renter.one@example.com", "");
        row(renters, 2, "Sample Renter Two", "", "sample.renter.two@example.com", "");

        if (!withContracts) return wb;

        Sheet contracts = wb.createSheet("Contracts");
        row(contracts, 0, "ContractNumber", "EjariNumber", "PropertyName", "BuildingName", "UnitNumber",
                "RenterEmail", "ContractDate", "StartDate", "EndDate", "GracePeriodDays",
                "LineNo", "ChargeTypeCode", "CreditAccount", "GrossAmount", "DiscountAmount",
                "VatApplicable", "Narration");
        row(contracts, 1, "SAMPLE-0001", "EJ-1", "Sample Tower", "", "101", "sample.renter.one@example.com",
                "2026-09-11", "2026-09-24", "2027-09-23", "5",
                "1", "RENT", "Advance Rent - ST1", "51000.00", "0", "false", "Annual rent");
        row(contracts, 2, "SAMPLE-0001", "", "", "", "", "", "", "", "", "",
                "2", "SECURITY_DEPOSIT", "", "5000.00", "0", "false", "Security deposit");
        row(contracts, 3, "SAMPLE-0002", "EJ-2", "Sample Tower", "", "102", "sample.renter.two@example.com",
                "2026-09-11", "2026-10-01", "2027-09-30", "",
                "1", "RENT", "", "21000.00", "0", "true", "Annual rent");

        Sheet cheques = wb.createSheet("Cheques");
        row(cheques, 0, "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate",
                "PayeeBank", "DebitAccount", "Amount", "Narration", "Mode", "Status",
                "DepositedDate", "ClearedDate", "BouncedDate");
        row(cheques, 1, "SAMPLE-0001", "1", "2026-09-11", "100001", "2026-09-24", "Sample Bank", "",
                "31000.00", "Rent - 1st Installment", "PDC", "CLEARED", "2026-09-24", "2026-09-25", "");
        row(cheques, 2, "SAMPLE-0001", "2", "2026-09-11", "100002", "2027-03-24", "Sample Bank", "",
                "25000.00", "Rent - 2nd Installment", "PDC", "REGISTERED", "", "", "");
        row(cheques, 3, "SAMPLE-0002", "1", "2026-09-11", "100201", "2026-10-01", "Sample Bank", "",
                "22050.00", "Rent - 1st Installment", "PDC", "REGISTERED", "", "", "");
        return wb;
    }

    private List<ImportErrorDTO> errors(Workbook wb) {
        return new ContractImportValidator().validate(wb, lookups()).errors();
    }

    private List<ImportErrorDTO> warnings(Workbook wb) {
        return new ContractImportValidator().validate(wb, lookups()).warnings();
    }

    private static void set(Workbook wb, String sheet, int row, int col, String value) {
        Row r = wb.getSheet(sheet).getRow(row);
        if (r.getCell(col) == null) r.createCell(col);
        r.getCell(col).setCellValue(value);
    }

    // ------------------------------------------------------------------
    // discrimination
    // ------------------------------------------------------------------

    @Test
    void aWorkbookWithAContractsSheetIsAV2Workbook() throws Exception {
        try (Workbook v2 = workbook(true); Workbook v1 = workbook(false)) {
            assertThat(ContractImportValidator.isV2Workbook(v2)).isTrue();
            assertThat(ContractImportValidator.isV2Workbook(v1)).isFalse();
        }
    }

    @Test
    void aWellFormedWorkbookHasNoErrors() throws Exception {
        try (Workbook wb = workbook(true)) {
            assertThat(errors(wb)).isEmpty();
        }
    }

    @Test
    void aWorkbookWithoutAContractsSheetIsRejectedBeforeAnythingElseIsRead() throws Exception {
        try (Workbook wb = workbook(false)) {
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getMessage)
                    .contains(Tuple.tuple("Contracts", "Sheet 'Contracts' is missing"));
        }
    }

    // ------------------------------------------------------------------
    // the Σ rule (R5) — gross, not net
    // ------------------------------------------------------------------

    /** Σ cheque amounts must equal Σ (line net + its VAT), the rule LeasePostingService enforces at post. */
    @Test
    void chequesThatDoNotAddUpToTheContractValueAreAnErrorShowingBothFigures() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 7, "20000.00");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Cheques", "Amount"));
            assertThat(errors(wb)).extracting(ImportErrorDTO::getMessage)
                    .anyMatch(m -> m.contains("51000.00") && m.contains("56000.00"));
        }
    }

    /**
     * The VAT-bearing contract is the one the brief's net-only rule let through:
     * 21,000 of rent plus 5% is 22,050, and a cheque grid that adds up to the NET
     * passes import validation and then fails bulk post.
     */
    @Test
    void aVatBearingContractIsMeasuredAgainstItsGrossNotItsNet() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 3, 7, "21000.00");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getMessage)
                    .anyMatch(m -> m.contains("SAMPLE-0002") && m.contains("22050.00"));
        }
    }

    /** A deposit line is never taxed, whatever its flag says — LeaseVat owns that rule. */
    @Test
    void aVatFlaggedDepositLineAddsNoVatToTheExpectedTotal() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 15, "true");
            assertThat(errors(wb)).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // cross-sheet references
    // ------------------------------------------------------------------

    @Test
    void aChequeReferencingAnUnknownContractIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 0, "NOPE/1");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Cheques", "ContractNumber"));
        }
    }

    @Test
    void aContractWhoseUnitIsNotOnTheUnitsSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 4, "999");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("UnitNumber");
        }
    }

    @Test
    void aContractWhoseRenterIsNotOnTheRentersSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 5, "nobody@example.com");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("RenterEmail");
        }
    }

    @Test
    void aContractWithNoChequeRowsIsAWarningNotAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 3, 0, "SAMPLE-0001");
            set(wb, "Cheques", 3, 3, "100003");
            set(wb, "Cheques", 3, 7, "0.01");
            // SAMPLE-0002 now has no rows at all; SAMPLE-0001's total no longer matches, which
            // is a separate error — the warning is what this pins.
            assertThat(warnings(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Cheques", "ContractNumber"));
            assertThat(warnings(wb)).extracting(ImportErrorDTO::getMessage)
                    .anyMatch(m -> m.contains("SAMPLE-0002") && m.contains("cannot be posted"));
        }
    }

    // ------------------------------------------------------------------
    // dates
    // ------------------------------------------------------------------

    @Test
    void contractDateBeforeStartDateIsAllowedButEndBeforeStartIsNot() throws Exception {
        try (Workbook wb = workbook(true)) {
            assertThat(errors(wb)).isEmpty();          // the fixture already dates the contract before the start
            set(wb, "Contracts", 1, 8, "2026-09-01");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("EndDate");
        }
    }

    /** The PACT exports print dd-MM-yyyy; a pasted cell must not be a parse failure. */
    @Test
    void theClientsOwnDateFormatIsAccepted() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 6, "11-09-2026");
            set(wb, "Contracts", 1, 7, "24/09/2026");
            assertThat(errors(wb)).isEmpty();
        }
    }

    @Test
    void anUnparseableDateNamesTheColumnAndTheExpectedFormat() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 7, "next tuesday");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("StartDate");
                        assertThat((String) t.toList().get(1)).contains("YYYY-MM-DD");
                    });
        }
    }

    // ------------------------------------------------------------------
    // cheque statuses and their dates
    // ------------------------------------------------------------------

    @Test
    void anUnknownChequeStatusIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 10, "SETTLED");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("Status");
        }
    }

    @Test
    void aClearedChequeWithoutAClearedDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 12, "");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ClearedDate");
        }
    }

    /**
     * PACT's exports record a cheque's realisation, not the day it went to the bank,
     * so a sheet that gives only a cleared date is the ordinary case — it is banked
     * on the day it cleared rather than refused over a date nobody has (review I3).
     */
    @Test
    void aClearedPostDatedChequeWithoutADepositedDateIsBankedOnTheDayItCleared() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 11, "");
            assertThat(errors(wb)).isEmpty();
            assertThat(ContractImportValidator.depositedOnFor(ChequeMode.PDC, ChequeStatus.CLEARED,
                    null, LocalDate.of(2026, 9, 25), null))
                    .isEqualTo(LocalDate.of(2026, 9, 25));
        }
    }

    /** A cash or transfer receipt is never banked, so it never acquires a deposit date. */
    @Test
    void aReceiptThatIsNotAChequeIsNeverBanked() {
        assertThat(ContractImportValidator.depositedOnFor(ChequeMode.CASH, ChequeStatus.CLEARED,
                null, LocalDate.of(2026, 9, 25), null)).isNull();
    }

    /** A date the sheet DOES give is never overridden by the stand-in, in any branch. */
    @Test
    void anExplicitDepositedDateWins() {
        LocalDate typed = LocalDate.of(2026, 9, 24);
        assertThat(ContractImportValidator.depositedOnFor(ChequeMode.PDC, ChequeStatus.CLEARED,
                typed, LocalDate.of(2026, 9, 25), null)).isEqualTo(typed);
        assertThat(ContractImportValidator.depositedOnFor(ChequeMode.PDC, ChequeStatus.BOUNCED,
                typed, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26))).isEqualTo(typed);
        assertThat(ContractImportValidator.depositedOnFor(ChequeMode.PDC, ChequeStatus.DEPOSITED,
                typed, null, null)).isEqualTo(typed);
    }

    @Test
    void aDepositedChequeWithoutADepositedDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 10, "DEPOSITED");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("DepositedDate");
        }
    }

    /** The bounce date has no stand-in: nothing else on the row says when it was returned. */
    @Test
    void aBouncedChequeNeedsItsBouncedDate() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 10, "BOUNCED");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("BouncedDate");
        }
    }

    @Test
    void aBouncedChequeWithOnlyItsBouncedDateIsBankedOnThatDay() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 10, "BOUNCED");
            set(wb, "Cheques", 2, 13, "2027-03-26");
            assertThat(errors(wb)).isEmpty();
            assertThat(ContractImportValidator.depositedOnFor(ChequeMode.PDC, ChequeStatus.BOUNCED,
                    null, null, LocalDate.of(2027, 3, 26)))
                    .isEqualTo(LocalDate.of(2027, 3, 26));
        }
    }

    @Test
    void aRegisteredChequeThatCarriesALifecycleDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 12, "2027-03-25");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ClearedDate");
        }
    }

    @Test
    void lifecycleDatesMustRunInOrder() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 12, "2026-09-23");   // cleared before it was deposited
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("ClearedDate");
                        assertThat((String) t.toList().get(1)).contains("DepositedDate");
                    });
        }
    }

    /** Only a post-dated cheque is deposited; cash and transfers are received straight to CLEARED. */
    @Test
    void aNonChequeReceiptCannotBeDeposited() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 3, "");             // a CASH row carries no cheque number
            set(wb, "Cheques", 2, 9, "CASH");
            set(wb, "Cheques", 2, 10, "DEPOSITED");
            set(wb, "Cheques", 2, 11, "2027-03-25");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("Status");
                        assertThat((String) t.toList().get(1)).contains("CASH");
                    });
        }
    }

    @Test
    void anOnlineReceiptIsRefusedBecauseTheGatewayCreatesThoseRows() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 3, "");
            set(wb, "Cheques", 2, 9, "ONLINE");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("Mode");
        }
    }

    // ------------------------------------------------------------------
    // cheque numbers and sequence
    // ------------------------------------------------------------------

    @Test
    void twoChequeRowsOfOneContractCannotShareAChequeNumber() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 3, "100001");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("ChequeNumber");
                        assertThat((String) t.toList().get(1)).contains("100001");
                    });
        }
    }

    /** Different leases may reuse a number — ux_cheques_lease_number is per lease. */
    @Test
    void twoContractsMayReuseAChequeNumber() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 3, 3, "100001");
            assertThat(errors(wb)).isEmpty();
        }
    }

    @Test
    void aPostDatedChequeWithoutANumberIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 3, "");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ChequeNumber");
        }
    }

    @Test
    void aCashReceiptCannotCarryAChequeNumber() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 9, "CASH");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("ChequeNumber");
                        assertThat((String) t.toList().get(1)).contains("CASH");
                    });
        }
    }

    @Test
    void aDuplicateSeqNoWithinAContractIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 1, "1");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("SeqNo");
        }
    }

    // ------------------------------------------------------------------
    // lines
    // ------------------------------------------------------------------

    @Test
    void anUnknownChargeTypeCodeIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 11, "MAGIC");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ChargeTypeCode");
        }
    }

    /** The catalogue is looked up case-insensitively (R8), so a lower-case code is fine. */
    @Test
    void aChargeTypeCodeIsMatchedWithoutRegardToCase() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 11, "rent");
            assertThat(errors(wb)).isEmpty();
        }
    }

    @Test
    void aDuplicateLineNoWithinAContractIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 10, "1");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("LineNo");
        }
    }

    @Test
    void aDiscountLargerThanTheGrossIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 14, "6000.00");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("DiscountAmount");
        }
    }

    @Test
    void aZeroGrossAmountIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 13, "0");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("GrossAmount");
        }
    }

    /** external_contract_ref is varchar(64); a longer reference would fail at insert. */
    @Test
    void aContractNumberLongerThanTheColumnIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            String tooLong = "X".repeat(65);
            set(wb, "Contracts", 1, 0, tooLong);
            set(wb, "Cheques", 1, 0, tooLong);
            set(wb, "Cheques", 2, 0, tooLong);
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ContractNumber");
        }
    }

    // ------------------------------------------------------------------
    // account names (R10) — resolved by name, never guessed
    // ------------------------------------------------------------------

    @Test
    void anUnknownMappingAccountNameIsAnErrorNamingTheCellAndTheValue() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Properties", 1, 9, "Bank Of Nowhere");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("Properties");
                        assertThat(t.toList().get(1)).isEqualTo("BankAccount");
                        assertThat((String) t.toList().get(2)).contains("Bank Of Nowhere");
                    });
        }
    }

    @Test
    void anAmbiguousMappingAccountNameIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            chart.put("sample bank - st1", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Sample Bank - ST1", false, true, AccountType.ASSET, true));
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("BankAccount");
                        assertThat((String) t.toList().get(1)).contains("More than one");
                    });
        }
    }

    @Test
    void aGroupAccountCannotPlayARole() throws Exception {
        try (Workbook wb = workbook(true)) {
            chart.put("sample bank - st1", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Sample Bank - ST1", true, true, AccountType.ASSET, false));
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("BankAccount");
                        assertThat((String) t.toList().get(1)).contains("group");
                    });
        }
    }

    @Test
    void anAccountOfTheWrongTypeForItsRoleIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Properties", 1, 9, "Rental Income ST1");   // income leaf as the BANK account
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("BankAccount");
                        assertThat((String) t.toList().get(1)).contains("ASSET");
                    });
        }
    }

    @Test
    void aRoleTheSheetLeavesBlankIsReportedAsUnmapped() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Properties", 1, 10, "");
            assertThat(warnings(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("PdcReceivableAccount");
                        assertThat((String) t.toList().get(1)).contains("PDC_RECEIVABLE");
                    });
        }
    }

    @Test
    void aLineCreditAccountTheChartDoesNotHaveIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 12, "Rental Income Nowhere");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Contracts", "CreditAccount"));
        }
    }

    /**
     * A RENT line credits ADVANCE_RENT, which is a liability: rent is unearned when
     * the contract posts. Pointing the override at the income leaf is the mistake
     * the brief's own sample made, and LeaseService throws on it — which in a bulk
     * import would lose the workbook instead of naming the cell.
     */
    @Test
    void aCreditAccountOfTheWrongTypeForItsChargeTypeIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 12, "Rental Income ST1");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("CreditAccount");
                        assertThat((String) t.toList().get(1)).contains("LIABILITY");
                    });
        }
    }

    @Test
    void anInactiveAccountCannotBeNamed() throws Exception {
        try (Workbook wb = workbook(true)) {
            chart.put("sample bank - st1", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Sample Bank - ST1", false, false, AccountType.ASSET, false));
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("BankAccount");
                        assertThat((String) t.toList().get(1)).contains("inactive");
                    });
        }
    }

    @Test
    void aChequeDebitAccountTheChartDoesNotHaveIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 6, "Bank Of Nowhere");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Cheques", "DebitAccount"));
        }
    }

    // ------------------------------------------------------------------
    // duplicates, in the sheet and in the database (R10)
    // ------------------------------------------------------------------

    @Test
    void aPropertyNamedTwiceInTheSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            row(wb.getSheet("Properties"), 2, "Sample Tower", "", "DUBAI", "", "RESIDENTIAL", "");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Properties", "PropertyName"));
        }
    }

    /** PropertyAccountService resolves leaves by NAME, so a merge would join two towers' ledgers. */
    @Test
    void aPropertyThatAlreadyExistsInTheOrganisationIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            existingProperties.add("sample tower");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("PropertyName");
                        assertThat((String) t.toList().get(1)).contains("already exists");
                        // The instruction, not just the diagnosis (review I4).
                        assertThat((String) t.toList().get(1)).contains("September cut-over");
                        assertThat((String) t.toList().get(1)).contains("Discard that batch");
                    });
        }
    }

    @Test
    void aRenterEmailThatAlreadyExistsInTheOrganisationIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            existingRenterEmails.add("sample.renter.one@example.com");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Renters", "Email"));
        }
    }

    @Test
    void aRenterEmailRepeatedInTheSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Renters", 2, 2, "sample.renter.one@example.com");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Renters", "Email"));
        }
    }

    @Test
    void aUnitListedTwiceOnTheUnitsSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Units", 2, 2, "101");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Units", "UnitNumber"));
        }
    }

    // ------------------------------------------------------------------
    // one live tenancy per unit
    // ------------------------------------------------------------------

    /** Two contracts on one unit would be two live tenancies the moment the batch posts. */
    @Test
    void twoContractsOnOneUnitIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 3, 4, "101");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("UnitNumber");
                        assertThat((String) t.toList().get(1)).contains("SAMPLE-0001");
                    });
        }
    }

    @Test
    void aUnitAlreadyHeldByALiveLeaseIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            liveLeases.put("sample tower||101", "already let to Someone Else until 2027-01-31");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("UnitNumber");
                        assertThat((String) t.toList().get(1)).contains("Someone Else");
                    });
        }
    }

    // ------------------------------------------------------------------
    // one ContractNumber is one contract (review C1)
    // ------------------------------------------------------------------

    /**
     * The exact silent merge the review found: two DIFFERENT contracts typed with
     * one number became ONE lease on the first one's unit, holding both contracts'
     * lines, with the second renter never leased and their unit never claimed. The
     * Sigma guard passed because both sides merged under the same key.
     */
    @Test
    void twoDifferentContractsTypedWithOneNumberAreRefused() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 3, 0, "SAMPLE-0001");   // row 4 is a different contract
            set(wb, "Cheques", 3, 0, "SAMPLE-0001");
            set(wb, "Cheques", 3, 1, "3");

            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("Contracts");
                        assertThat((String) t.toList().get(2)).contains("SAMPLE-0001");
                        assertThat((String) t.toList().get(2)).contains("row 2");
                    });
            // And ONLY that: the merged contract's lines and cheques no longer add
            // up either, but a second error about money would send the accountant
            // to the wrong cell entirely (review M7).
            assertThat(errors(wb)).extracting(ImportErrorDTO::getMessage)
                    .noneMatch(m -> m.contains("but the contract's lines come to"));
        }
    }

    /**
     * A contract whose identity is broken gets no SECOND complaint about its money.
     * Merged under one number its lines and cheques will not agree, but that
     * arithmetic is a consequence of the merge, not a fault in the figures, and
     * sending the accountant to a cheque cell would be sending them to the wrong
     * one (review M7).
     */
    @Test
    void aContractWithAHeaderConflictReportsNoChequeMismatch() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 3, 0, "SAMPLE-0001");
            set(wb, "Cheques", 3, 0, "SAMPLE-0001");
            set(wb, "Cheques", 3, 1, "3");
            set(wb, "Cheques", 3, 7, "19999.00");   // and now the merged sides really do differ

            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("UnitNumber");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getMessage)
                    .noneMatch(m -> m.contains("but the contract's lines come to"));
        }
    }

    /** The conflicting cell is named, not just the row. */
    @Test
    void aLaterRowThatContradictsItsContractsHeaderNamesTheColumn() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 4, "102");          // line 2 claims a different unit
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Contracts", "UnitNumber"));
        }
    }

    @Test
    void aLaterRowThatContradictsTheHeaderDatesIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 7, "2026-10-15");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("StartDate");
        }
    }

    /** Blank is how a continuation row is meant to look, and stays fine. */
    @Test
    void aLaterRowRepeatingTheHeaderVerbatimIsFine() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 2, 2, "Sample Tower");
            set(wb, "Contracts", 2, 4, "101");
            set(wb, "Contracts", 2, 5, "sample.renter.one@example.com");
            set(wb, "Contracts", 2, 6, "2026-09-11");
            assertThat(errors(wb)).isEmpty();
        }
    }

    /** A number this organisation already imported would be a second lease for one contract. */
    @Test
    void aContractNumberTheOrganisationAlreadyHoldsIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            existingContractRefs.put("SAMPLE-0001", "import batch 'September cut-over' (REVERSED)");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("ContractNumber");
                        assertThat((String) t.toList().get(1)).contains("September cut-over");
                    });
        }
    }

    /** M7: a structural error must not also fire a misleading Sigma error. */
    @Test
    void anOverLongContractNumberDoesNotAlsoReportAChequeMismatch() throws Exception {
        try (Workbook wb = workbook(true)) {
            String tooLong = "X".repeat(65);
            set(wb, "Contracts", 1, 0, tooLong);
            set(wb, "Contracts", 2, 0, tooLong);
            set(wb, "Cheques", 1, 0, tooLong);
            set(wb, "Cheques", 2, 0, tooLong);
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("ContractNumber");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getMessage)
                    .noneMatch(m -> m.contains("but the contract's lines come to"));
        }
    }

    // ------------------------------------------------------------------
    // the grid that came back is the grid that went in
    // ------------------------------------------------------------------

    /**
     * Half a grid stamped with its replay instructions and half not would be a
     * batch that posts some cheques on the days they really moved and the rest on
     * the day somebody clicked Post (review M4).
     */
    @Test
    void aGridThatComesBackADifferentLengthIsRefusedRatherThanHalfStamped() {
        org.assertj.core.api.Assertions.assertThatCode(
                        () -> ContractImportPersistService.requireWholeGrid("SAMPLE-0001", 3, 3))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ContractImportPersistService.requireWholeGrid("SAMPLE-0001", 3, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SAMPLE-0001")
                .hasMessageContaining("partial grid");
    }

    // ------------------------------------------------------------------
    // every error carries its address
    // ------------------------------------------------------------------

    @Test
    void everyErrorNamesItsSheetRowAndColumn() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Contracts", 1, 11, "MAGIC");
            set(wb, "Cheques", 1, 10, "SETTLED");
            set(wb, "Properties", 1, 9, "Bank Of Nowhere");
            assertThat(errors(wb)).isNotEmpty().allSatisfy(e -> {
                assertThat(e.getSheet()).isNotBlank();
                assertThat(e.getRow()).isGreaterThan(0);
                assertThat(e.getField()).isNotBlank();
                assertThat(e.getMessage()).isNotBlank();
            });
        }
    }
}
