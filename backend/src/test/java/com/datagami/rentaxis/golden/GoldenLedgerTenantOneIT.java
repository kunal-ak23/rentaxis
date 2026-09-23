package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first golden replay: one real contract of the client's, driven through the
 * built v2 engine and diffed against their own General Ledger export, row by row.
 *
 * <p>This is the acceptance gate for the migration. If it passes, their accountant
 * can open our Tenant Ledger next to their old one and read the same document
 * sequence, the same counter accounts and the same running balances. Three columns
 * differ on purpose and only three — see the header of
 * {@code golden/tenant-one-ledger.csv}.</p>
 *
 * <h2>The contract, and why this one</h2>
 * One year, seven post-dated cheques all registered on the contract date, five
 * clearances spread over ten months and <em>two cheques that bounce after
 * clearing</em> — the one cheque transition whose journal credits the bank rather
 * than PDC receivable (spec §7.2), and one the built register only permits in
 * {@code PDC} mode, which is why every row here is a PDC. Thirteen recognition
 * entries carry the rent out of advance rent day by day.
 *
 * <h2>Anonymisation (rulings P5-R11 / P5-R13)</h2>
 * Only the <b>amounts</b>, the <b>dates</b> and the <b>document sequence</b> come
 * from the client's export. The property is "Sample Tower", the renter
 * "Sample Renter One", the contract "SAMPLE-25/001". Every account name in the
 * fixture is what {@link PropertyAccountService}'s own template patterns produce
 * for a property of that name — derived from the patterns, never typed from the
 * export — and {@link #theAccountNamesAreTheOnesTheTemplateDerives()} pins that.
 *
 * <h2>How each document type is driven</h2>
 * <ul>
 *   <li>{@code TCO} — {@code LeaseService.createDraftLease} then
 *       {@code LeasePostingService.post} (ruling P5-R3). {@code CreateLeaseDTO}
 *       silently drops {@code cheques[]}, so the explicit grid goes in separately
 *       through {@code ChequeGenerationService.saveRows}.</li>
 *   <li>{@code PDR} — written by the post, one per grid row, dated the row's
 *       {@code postingDate}.</li>
 *   <li>{@code CRT} / {@code CBR} — {@code ChequeService.deposit/clear/bounce}
 *       with {@code ChequeActionRequest} (ruling P5-R5).</li>
 *   <li>{@code CIL} — {@code RecognitionService.runTo(to, false)}, the service and
 *       not the endpoint: {@code RecognitionController} refuses a {@code to} in the
 *       future by design and this replay ends in a month that has not happened
 *       yet (ruling P5-R4). Nothing on this path reads the wall clock for a
 *       journal date, so the run is deterministic without one being injected.</li>
 * </ul>
 */
@SpringBootTest
@Tag("golden")
class GoldenLedgerTenantOneIT extends AbstractPostgresIT {

    private static final String LEDGER = "golden/tenant-one-ledger.csv";
    private static final String RECOGNITION = "golden/tenant-one-recognition.csv";

    /** The anonymised property; every account name below is derived from it. */
    private static final String PROPERTY = "Sample Tower";
    private static final String RENTER = "Sample Renter One";
    private static final String CONTRACT_REF = "SAMPLE-25/001";

    private static final String RENT_RECEIVABLE = "Rent Receivable - " + PROPERTY;
    private static final String ADVANCE_RENT = "Advance Rent - " + PROPERTY;
    private static final String RENTAL_INCOME = "Rental Income " + PROPERTY;
    private static final String PDC_RECEIVABLE = "PDC Receivable " + PROPERTY;
    private static final String BANK = "Emirates Islamic - " + PROPERTY;
    private static final String SECURITY_DEPOSIT = "Security Deposit " + PROPERTY;
    private static final String ADMIN_FEE = "Admin Fee - " + PROPERTY;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2025, 8, 28);
    private static final LocalDate TERM_START = LocalDate.of(2025, 9, 5);
    private static final LocalDate TERM_END = LocalDate.of(2026, 9, 4);
    private static final LocalDate CUT_OFF = LocalDate.of(2026, 9, 30);

    /** Wide enough to hold the whole tenancy; the ledger query is inclusive of both ends. */
    private static final LocalDate LEDGER_FROM = LocalDate.of(2025, 1, 1);
    private static final LocalDate LEDGER_TO = LocalDate.of(2027, 12, 31);

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PenaltyAssessmentRepository penalties;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired ChargeTypeService chargeTypes;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseService leases;
    @Autowired ChequeGenerationService grid;
    @Autowired LeasePostingService posting;
    @Autowired ChequeService cheques;
    @Autowired RecognitionService recognition;
    @Autowired LedgerQueryService ledger;

    UUID propertyId;
    UUID renterId;
    UUID leaseId;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures ground = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                properties, accounts, propertyAccounts, chargeTypes);
        ground.newTenant();
        // LeaseAccessPolicy fails closed: without an authentication every read of
        // the lease answers "Lease not found". See LeaseTestFixtures' own note.
        LeaseTestFixtures.authenticateAsTenantAdmin();
        ground.seedAccounting();

        // The books open before the contract date, otherwise the period lock rejects
        // the TCO. setBooksStartDate derives the lock; lockThrough states it anyway,
        // because the replay's earliest journal being outside the lock is the point.
        fiscal.setBooksStartDate(LocalDate.of(2025, 8, 1));
        fiscal.lockThrough(LocalDate.of(2025, 7, 31));

        // Through PropertyService, never the repository: creating a property is what
        // runs generateMissing and gives it the leaf accounts the fixture names.
        Property p = new Property();
        p.setNameEn(PROPERTY);
        p.setEmirate(Emirate.DUBAI);
        p.setCode("ST_" + UUID.randomUUID().toString().substring(0, 8));
        p.setTenantId(ground.tenantId());
        Property property = properties.createProperty(p);
        propertyId = property.getId();

        Unit unit = ground.createUnit(property, "1206");
        Renter renter = ground.createRenter(RENTER);
        renterId = renter.getId();

        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unit.getId());
        dto.setRenterId(renterId);
        dto.setContractDate(CONTRACT_DATE);
        dto.setStartDate(TERM_START);
        dto.setEndDate(TERM_END);
        dto.setFirstDueDate(TERM_START);
        dto.setGracePeriodDays(0);
        // Line order is the export's TCO row order on the receivable, which is what
        // line_no — and therefore the printed order — follows.
        dto.setLines(List.of(
                line("SECURITY_DEPOSIT", "2750.00"),
                line("RENT", "55000.00"),
                line("ADMIN_FEE", "300.00")));
        leaseId = leases.createDraftLease(dto).getId();

        // The export's contract string. CreateLeaseDTO carries no field for it and
        // Lease.contractNumber is a server-assigned number, so it goes where plan 4
        // put PACT's references: leases.external_contract_ref (ruling P5-R3).
        leaseRepo.findById(leaseId).ifPresent(l -> {
            l.setExternalContractRef(CONTRACT_REF);
            leaseRepo.save(l);
        });

        // Seven post-dated cheques, all registered on the contract date. The first
        // folds the 300 admin fee into the 9,000 first rent instalment, as the
        // export's does. Σ = 58,050.00 = the contract value, which post() requires.
        grid.saveRows(leaseId, List.of(
                cheque(1, "000101", LocalDate.of(2025, 9, 3), "9300.00", "Rent - 1st Installment"),
                cheque(2, "000102", LocalDate.of(2025, 10, 6), "2750.00", "Security Deposit"),
                cheque(3, "000103", LocalDate.of(2025, 11, 5), "9000.00", "Rent - 2nd Installment"),
                cheque(4, "000104", LocalDate.of(2026, 1, 5), "9000.00", "Rent - 3rd Installment"),
                cheque(5, "000105", LocalDate.of(2026, 3, 5), "9000.00", "Rent - 4th Installment"),
                cheque(6, "000106", LocalDate.of(2026, 5, 5), "9000.00", "Rent - 5th Installment"),
                cheque(7, "000107", LocalDate.of(2026, 7, 6), "10000.00", "Rent - 6th Installment")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /**
     * One grid row. {@code postingDate} is the contract date on every one of them —
     * the whole book of cheques was handed over when the contract was signed, and
     * the {@code PDR} is dated when the landlord took the paper, not when it matures.
     *
     * <p>{@code ChequeMode.PDC} is load-bearing rather than a default written out:
     * {@code ChequeService.bounce} refuses a post-clearing bounce on anything else,
     * and rows 6 and 7 do exactly that.</p>
     */
    private static ChequeRowInput cheque(int seq, String number, LocalDate chequeDate,
                                         String amount, String narration) {
        return new ChequeRowInput(null, seq, CONTRACT_DATE, number, chequeDate,
                "Emirates Islamic", null, null, new BigDecimal(amount), narration, ChequeMode.PDC);
    }

    /** The contract's whole life, in the order the client's office did it. */
    private void replay() {
        posting.post(leaseId);

        List<ChequeDTO> rows = grid.list(leaseId);
        assertThat(rows).hasSize(7);

        // 1-5 bank and clear cleanly, each on its own maturity date.
        for (int i = 0; i < 5; i++) {
            bankAndClear(rows.get(i));
        }
        // 6 and 7 clear and are returned by the bank the same day (spec §7.2,
        // CLEARED -> BOUNCED: Dr rent receivable / Cr the bank it was banked into,
        // not PDC receivable, which the CRT has already settled).
        for (int i = 5; i < 7; i++) {
            ChequeDTO c = bankAndClear(rows.get(i));
            cheques.bounce(c.id(), new ChequeActionRequest(c.chequeDate(), null,
                    ChequeFailureReason.BOUNCE, null));
        }

        recognition.runTo(CUT_OFF, false);
    }

    private ChequeDTO bankAndClear(ChequeDTO row) {
        cheques.deposit(row.id(), ChequeActionRequest.on(row.chequeDate()));
        cheques.clear(row.id(), ChequeActionRequest.on(row.chequeDate()));
        return row;
    }

    /** The renter's ledger, one entry per account, keyed by the name the export prints. */
    private Map<String, AccountLedgerDTO> renterLedgerByAccount() {
        return ledger.renterLedger(renterId, LEDGER_FROM, LEDGER_TO).stream()
                .collect(Collectors.toMap(AccountLedgerDTO::accountName, Function.identity(),
                        (a, b) -> { throw new IllegalStateException("duplicate account " + a.accountName()); },
                        LinkedHashMap::new));
    }

    // ------------------------------------------------------------------
    // the gate
    // ------------------------------------------------------------------

    /**
     * The account names the fixture is written in are the template's, not a
     * transcription of the client's chart.
     *
     * <p>Ruling P5-R11 says the anonymised fixture must name the accounts
     * {@code PropertyAccountService}'s patterns produce for "Sample Tower". This is
     * where that stops being a claim: if a pattern is edited, this fails and names
     * the account, instead of the whole replay failing on row 1 of an account the
     * product no longer has.</p>
     */
    @Test
    void theAccountNamesAreTheOnesTheTemplateDerives() {
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId).getName()).isEqualTo(RENT_RECEIVABLE);
        assertThat(resolver.resolve(AccountRole.ADVANCE_RENT, propertyId).getName()).isEqualTo(ADVANCE_RENT);
        assertThat(resolver.resolve(AccountRole.RENTAL_INCOME, propertyId).getName()).isEqualTo(RENTAL_INCOME);
        assertThat(resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId).getName()).isEqualTo(PDC_RECEIVABLE);
        assertThat(resolver.resolve(AccountRole.BANK, propertyId).getName()).isEqualTo(BANK);
        assertThat(resolver.resolve(AccountRole.SECURITY_DEPOSIT, propertyId).getName()).isEqualTo(SECURITY_DEPOSIT);
        assertThat(resolver.resolve(AccountRole.ADMIN_FEE, propertyId).getName()).isEqualTo(ADMIN_FEE);

        assertThat(GoldenLedgerFixture.ledgerByAccount(LEDGER).keySet())
                .as("the fixture is written in the template's names")
                .containsExactlyInAnyOrder(RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME,
                        PDC_RECEIVABLE, BANK, SECURITY_DEPOSIT, ADMIN_FEE);

        assertThat(leaseRepo.findById(leaseId).orElseThrow().getExternalContractRef())
                .isEqualTo(CONTRACT_REF);
    }

    @Test
    void everyAccountMatchesTheExportLineForLine() {
        replay();

        Map<String, List<GoldenRow>> expected = GoldenLedgerFixture.ledgerByAccount(LEDGER);
        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();

        // Exactly, not "contains": an eighth account — output VAT on a charge type
        // whose default flipped, a penalty the rule engine decided to post — would
        // otherwise never be compared against anything (ruling P5-R14).
        assertThat(actual.keySet())
                .as("the renter's ledger must touch exactly the accounts the export does")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach((account, rows) ->
                LedgerDiff.assertAccountMatches(account, rows, actual.get(account)));
    }

    /**
     * The TCO narration, the one documented deviation the comparator deliberately
     * cannot see (ruling P5-R2).
     *
     * <p>{@code LedgerDiff} tolerates any product narration where the fixture's TCO
     * narration is blank, and the export's Remarks cell is blank on every contract
     * row — so without this the replay would pin nothing at all about what we write
     * there. What we write is the charge type's English name, on purpose: a blank
     * cell is worse for the reader than "Security Deposit".</p>
     */
    @Test
    void theContractLinesCarryTheChargeTypesEnglishName() {
        replay();

        List<LedgerRowDTO> tco = renterLedgerByAccount().get(RENT_RECEIVABLE).rows().stream()
                .filter(r -> "TCO".equals(r.docType()))
                .toList();

        assertThat(tco).extracting(LedgerRowDTO::narration)
                .as("the deviation from the export's blank Remarks cell, in lease-line order")
                .containsExactly("Security Deposit", "Rent", "Admin Fee");
    }

    @Test
    void theReportTotalMatchesTheExport() {
        replay();

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        BigDecimal debit = actual.values().stream().map(AccountLedgerDTO::totalDebit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = actual.values().stream().map(AccountLedgerDTO::totalCredit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The export's own REPORT TOTAL row. The per-day rule redistributes the CIL
        // rows but does not change their sum, so this number is unaffected by it.
        assertThat(debit).isEqualByComparingTo("248150.00");
        assertThat(credit).isEqualByComparingTo(debit);

        // And the fixture's own columns, so a transcription slip reads as a bad
        // fixture rather than as a product failure.
        List<GoldenRow> fixture = GoldenLedgerFixture.ledger(LEDGER);
        assertThat(GoldenLedgerFixture.reportTotal(fixture)).isEqualByComparingTo("248150.00");
        assertThat(GoldenLedgerFixture.creditTotal(fixture)).isEqualByComparingTo("248150.00");
    }

    /**
     * The two bounces reach finance as a <em>proposal</em> and put nothing on the
     * books (pre-flight P-3).
     *
     * <p>Auto-proposal of cheque-return fines is on by default and the default
     * threshold is two, so the second returned cheque raises a 500 AED assessment —
     * {@code PROPOSED}, which posts no journal and creates no account. That is why
     * the export's seven accounts are still seven. The day a rule posts on its own,
     * {@link #everyAccountMatchesTheExportLineForLine()} fails on an eighth account
     * nobody can place; this says in advance what is expected to be there instead.</p>
     */
    @Test
    void theTwoBouncesProposeAFineAndPostNothing() {
        replay();

        assertThat(penalties.findByLease_Id(leaseId))
                .as("the second returned cheque proposes one fine; neither posts")
                .hasSize(1)
                .allSatisfy(a -> {
                    assertThat(a.getStatus()).isEqualTo(PenaltyAssessmentStatus.PROPOSED);
                    assertThat(a.getReason()).isEqualTo(PenaltyReason.CHEQUE_RETURN);
                });
    }

    @Test
    void theTrialBalanceBalancesAndShowsTheExpectedClosingPositions() {
        replay();

        // Tenant-wide on purpose: a property-filtered trial balance cannot see a
        // line posted without the property dimension (Task 3 review I-1).
        List<TrialBalanceRowDTO> tb = ledger.trialBalance(CUT_OFF, null);
        // Seven, so an eighth account carrying a posted line — one without the
        // renter dimension, which the renter ledger cannot see at all — fails here
        // rather than going unnoticed (Task 2 review M-2).
        assertThat(tb).hasSize(7);

        BigDecimal debit = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);

        Map<String, BigDecimal> balance = tb.stream()
                .collect(Collectors.toMap(TrialBalanceRowDTO::name, TrialBalanceRowDTO::balance));
        // Two bounced cheques (9,000 + 10,000) are still owed; everything else settled.
        assertThat(balance.get(RENT_RECEIVABLE)).isEqualByComparingTo("19000.00");
        assertThat(balance.get(BANK)).isEqualByComparingTo("39050.00");
        assertThat(balance.get(PDC_RECEIVABLE)).isEqualByComparingTo("0.00");
        assertThat(balance.get(ADVANCE_RENT)).isEqualByComparingTo("0.00");
        assertThat(balance.get(RENTAL_INCOME)).isEqualByComparingTo("-55000.00");
        assertThat(balance.get(SECURITY_DEPOSIT)).isEqualByComparingTo("-2750.00");
        assertThat(balance.get(ADMIN_FEE)).isEqualByComparingTo("-300.00");
    }

    @Test
    void theRecognitionScheduleFollowsThePerDayRule() {
        replay();

        List<LedgerDiff.RecognitionRow> got = recognition.scheduleFor(leaseId).stream()
                .map(e -> new LedgerDiff.RecognitionRow(e.periodStart(), e.periodEnd(), e.days(), e.amount()))
                .toList();

        LedgerDiff.assertRecognitionMatches(GoldenLedgerFixture.recognition(RECOGNITION), got);

        // 55,000 over 365 days, the remainder in the last slice — never a 366th day
        // of rent, and never a 366th day of term.
        assertThat(got.stream().map(LedgerDiff.RecognitionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("55000.00");
        assertThat(got.stream().mapToInt(LedgerDiff.RecognitionRow::days).sum()).isEqualTo(365);
        assertThat(recognition.scheduleFor(leaseId)).extracting(RecognitionEntryDTO::status)
                .allMatch(s -> "POSTED".equals(s.name()));
    }

    @Test
    void thePostedContractsRenterLedgerNetsToZeroBeforeAnyCheque() {
        // Spec §6.4: "After posting the renter's ledger nets to zero, as in PACT."
        // Asserted before the cheque lifecycle runs, because that is the only moment
        // it holds — the first clearance moves money into the bank.
        posting.post(leaseId);

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        assertThat(actual.get(RENT_RECEIVABLE).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(actual.get(PDC_RECEIVABLE).closingBalance()).isEqualByComparingTo("58050.00");
    }
}
