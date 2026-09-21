package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

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

    ContractImportValidatorTest() {
        leaf("Rental Income Tulip 7", AccountType.INCOME);
        leaf("Rent Receivable - Tulip 7", AccountType.ASSET);
        leaf("Advance Rent - Tulip 7", AccountType.LIABILITY);
        leaf("Emirates Islamic - Tulip 7", AccountType.ASSET);
        leaf("PDC Receivable Tulip 7", AccountType.ASSET);
        leaf("Security Deposit Tulip 7", AccountType.LIABILITY);
        leaf("Admin Fee - Tulip 7", AccountType.INCOME);
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

            @Override public boolean propertyExists(String nameEn) {
                return existingProperties.stream().anyMatch(n -> n.equalsIgnoreCase(nameEn));
            }

            @Override public boolean renterExists(String email) {
                return existingRenterEmails.stream().anyMatch(e -> e.equalsIgnoreCase(email));
            }

            @Override public String liveLeaseOn(String propertyName, String buildingName, String unitNumber) {
                return liveLeases.get((propertyName + "|" + buildingName + "|" + unitNumber)
                        .toLowerCase(Locale.ROOT));
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
     * Two contracts on one property. TLP7/681 is VAT-free — 51,000 rent + 5,000
     * deposit against two cheques of 31,000 and 25,000. TLP7/682 carries VAT on its
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
        row(props, 1, "Tulip Oasis 7", "", "DUBAI", "", "RESIDENTIAL", "",
                "Rental Income Tulip 7", "Rent Receivable - Tulip 7", "Advance Rent - Tulip 7",
                "Emirates Islamic - Tulip 7", "PDC Receivable Tulip 7", "Security Deposit Tulip 7");

        Sheet units = wb.createSheet("Units");
        row(units, 0, "PropertyName", "BuildingName", "UnitNumber", "UnitType", "SizeSqft", "ExpectedRent");
        row(units, 1, "Tulip Oasis 7", "", "101", "BHK1", "", "");
        row(units, 2, "Tulip Oasis 7", "", "102", "BHK1", "", "");

        Sheet renters = wb.createSheet("Renters");
        row(renters, 0, "Name", "NameAr", "Email", "Phone");
        row(renters, 1, "Islam Mamanov", "", "islam@example.com", "");
        row(renters, 2, "Anum Ishtiaq", "", "anum@example.com", "");

        if (!withContracts) return wb;

        Sheet contracts = wb.createSheet("Contracts");
        row(contracts, 0, "ContractNumber", "EjariNumber", "PropertyName", "BuildingName", "UnitNumber",
                "RenterEmail", "ContractDate", "StartDate", "EndDate", "GracePeriodDays",
                "LineNo", "ChargeTypeCode", "CreditAccount", "GrossAmount", "DiscountAmount",
                "VatApplicable", "Narration");
        row(contracts, 1, "TLP7/681", "EJ-1", "Tulip Oasis 7", "", "101", "islam@example.com",
                "2026-09-11", "2026-09-24", "2027-09-23", "5",
                "1", "RENT", "Advance Rent - Tulip 7", "51000.00", "0", "false", "Annual rent");
        row(contracts, 2, "TLP7/681", "", "", "", "", "", "", "", "", "",
                "2", "SECURITY_DEPOSIT", "", "5000.00", "0", "false", "Security deposit");
        row(contracts, 3, "TLP7/682", "EJ-2", "Tulip Oasis 7", "", "102", "anum@example.com",
                "2026-09-11", "2026-10-01", "2027-09-30", "",
                "1", "RENT", "", "21000.00", "0", "true", "Annual rent");

        Sheet cheques = wb.createSheet("Cheques");
        row(cheques, 0, "ContractNumber", "SeqNo", "PostingDate", "ChequeNumber", "ChequeDate",
                "PayeeBank", "DebitAccount", "Amount", "Narration", "Mode", "Status",
                "DepositedDate", "ClearedDate", "BouncedDate");
        row(cheques, 1, "TLP7/681", "1", "2026-09-11", "000101", "2026-09-24", "ENBD", "",
                "31000.00", "Rent - 1st Installment", "PDC", "CLEARED", "2026-09-24", "2026-09-25", "");
        row(cheques, 2, "TLP7/681", "2", "2026-09-11", "000102", "2027-03-24", "ENBD", "",
                "25000.00", "Rent - 2nd Installment", "PDC", "REGISTERED", "", "", "");
        row(cheques, 3, "TLP7/682", "1", "2026-09-11", "000201", "2026-10-01", "ENBD", "",
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
                    .anyMatch(m -> m.contains("TLP7/682") && m.contains("22050.00"));
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
            set(wb, "Cheques", 3, 0, "TLP7/681");
            set(wb, "Cheques", 3, 3, "000103");
            set(wb, "Cheques", 3, 7, "0.01");
            // TLP7/682 now has no rows at all; TLP7/681's total no longer matches, which
            // is a separate error — the warning is what this pins.
            assertThat(warnings(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Cheques", "ContractNumber"));
            assertThat(warnings(wb)).extracting(ImportErrorDTO::getMessage)
                    .anyMatch(m -> m.contains("TLP7/682") && m.contains("cannot be posted"));
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

    /** A cheque is banked before it clears, and the replay has to deposit it first. */
    @Test
    void aClearedPostDatedChequeWithoutADepositedDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 1, 11, "");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("DepositedDate");
        }
    }

    @Test
    void aDepositedChequeWithoutADepositedDateIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 10, "DEPOSITED");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField).contains("DepositedDate");
        }
    }

    @Test
    void aBouncedChequeNeedsBothItsDepositedAndItsBouncedDate() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 2, 10, "BOUNCED");
            assertThat(errors(wb)).extracting(ImportErrorDTO::getField)
                    .contains("DepositedDate", "BouncedDate");
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
            set(wb, "Cheques", 2, 3, "000101");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("ChequeNumber");
                        assertThat((String) t.toList().get(1)).contains("000101");
                    });
        }
    }

    /** Different leases may reuse a number — ux_cheques_lease_number is per lease. */
    @Test
    void twoContractsMayReuseAChequeNumber() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Cheques", 3, 3, "000101");
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
            chart.put("emirates islamic - tulip 7", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Emirates Islamic - Tulip 7", false, true, AccountType.ASSET, true));
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
            chart.put("emirates islamic - tulip 7", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Emirates Islamic - Tulip 7", true, true, AccountType.ASSET, false));
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
            set(wb, "Properties", 1, 9, "Rental Income Tulip 7");   // income leaf as the BANK account
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
            set(wb, "Contracts", 1, 12, "Rental Income Tulip 7");
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
            chart.put("emirates islamic - tulip 7", new ContractImportValidator.AccountMatch(
                    UUID.randomUUID(), "Emirates Islamic - Tulip 7", false, false, AccountType.ASSET, false));
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
            row(wb.getSheet("Properties"), 2, "Tulip Oasis 7", "", "DUBAI", "", "RESIDENTIAL", "");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Properties", "PropertyName"));
        }
    }

    /** PropertyAccountService resolves leaves by NAME, so a merge would join two towers' ledgers. */
    @Test
    void aPropertyThatAlreadyExistsInTheOrganisationIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            existingProperties.add("tulip oasis 7");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("PropertyName");
                        assertThat((String) t.toList().get(1)).contains("already exists");
                    });
        }
    }

    @Test
    void aRenterEmailThatAlreadyExistsInTheOrganisationIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            existingRenterEmails.add("islam@example.com");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getSheet, ImportErrorDTO::getField)
                    .contains(Tuple.tuple("Renters", "Email"));
        }
    }

    @Test
    void aRenterEmailRepeatedInTheSheetIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            set(wb, "Renters", 2, 2, "islam@example.com");
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
                        assertThat((String) t.toList().get(1)).contains("TLP7/681");
                    });
        }
    }

    @Test
    void aUnitAlreadyHeldByALiveLeaseIsAnError() throws Exception {
        try (Workbook wb = workbook(true)) {
            liveLeases.put("tulip oasis 7||101", "already let to Someone Else until 2027-01-31");
            assertThat(errors(wb))
                    .extracting(ImportErrorDTO::getField, ImportErrorDTO::getMessage)
                    .anySatisfy(t -> {
                        assertThat(t.toList().get(0)).isEqualTo("UnitNumber");
                        assertThat((String) t.toList().get(1)).contains("Someone Else");
                    });
        }
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
