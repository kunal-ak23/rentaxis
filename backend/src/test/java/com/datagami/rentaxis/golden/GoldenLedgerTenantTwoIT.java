package com.datagami.rentaxis.golden;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService.RecognitionRunResult;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.golden.GoldenLedgerFixture.GoldenRow;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
 * The second golden replay: another of the client's contracts, driven through the
 * built v2 engine and diffed against their own General Ledger export, row by row.
 *
 * <h2>The contract, and why this one</h2>
 * Where {@link GoldenLedgerTenantOneIT} covers a contract whose cheques clear and
 * two of which bounce afterwards, this one covers the opposite shape: <em>nothing
 * ever clears</em>. Five post-dated cheques are registered and the whole
 * 53,000.00 is still sitting in PDC receivable at the cut-off. Two further things
 * only this contract can prove:
 * <ul>
 *   <li><b>Per-row posting dates.</b> Each {@code PDR} is dated its own cheque
 *       row's {@code postingDate}, not the contract date — and the admin-fee
 *       cheque was taken in on 2026-09-11, <em>five days before the contract is
 *       dated</em>. Our ledger orders strictly by entry date, so that row leads
 *       the receivable; the export segregates post-dated receipts into a sub-block
 *       and prints it last (deviation D-d). The row set, the totals and the
 *       closing balance are identical — only the running-balance column on those
 *       rows differs, and the fixture carries our order with the balances
 *       recomputed.</li>
 *   <li><b>Manually remapped accounts</b> (ruling P5-R19, spec D2) — see below.</li>
 * </ul>
 * Thirteen recognition entries carry the rent out of advance rent day by day; this
 * is the contract the spec's own per-day reference table in §8.2 was computed
 * from, so the recognition assertion here is a direct check against the design.
 *
 * <h2>Anonymisation (rulings P5-R11 / P5-R13)</h2>
 * Only the <b>amounts</b>, the <b>dates</b> and the <b>document sequence</b> come
 * from the client's export. The property is "Sample Residences", the renter
 * "Sample Renter Two", the contract "SAMPLE-26/001"; the unit number and the
 * cheque numbers are invented, because neither appears in the ledger at all.
 *
 * <h2>The account set: three generated, two mapped by hand</h2>
 * The receivable, advance rent and rental income leaves are what
 * {@link PropertyAccountService}'s template patterns produce for a property of
 * this name — derived from the patterns, never typed. The other two roles are
 * <em>remapped</em> before anything is posted: an account is created by hand and
 * {@code setMapping} points {@code PDC_RECEIVABLE} and {@code ADMIN_FEE} at it, so
 * this replay exercises {@code AccountResolver}'s first hop landing on a mapped
 * leaf rather than a generated one. The client's own second contract shares two
 * generic tenant-wide accounts in exactly that spirit.
 * {@link #theTwoRemappedRolesPostToTheManualAccountsAndLeaveTheTemplateLeavesEmpty()}
 * proves both halves: the manual accounts carry every line, and the leaves the
 * template generated for those two roles carry none.
 *
 * <h2>How each document type is driven</h2>
 * <ul>
 *   <li>{@code TCO} — {@code LeaseService.createDraftLease} then
 *       {@code LeasePostingService.post} (ruling P5-R3). {@code CreateLeaseDTO}
 *       silently drops {@code cheques[]}, so the explicit grid goes in separately
 *       through {@code ChequeGenerationService.saveRows}.</li>
 *   <li>{@code PDR} — written by the post, one per grid row, dated the row's own
 *       {@code postingDate}.</li>
 *   <li>{@code CIL} — {@code RecognitionService.runTo(to, false)}, the service and
 *       not the endpoint: {@code RecognitionController} refuses a {@code to} in
 *       the future by design and this replay ends in a month that has not happened
 *       yet (ruling P5-R4). Nothing on this path reads the wall clock for a
 *       journal date, so the run is deterministic without one being injected.</li>
 *   <li>{@code CRT} / {@code CBR} — none. No cheque is ever banked.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@Tag("golden")
class GoldenLedgerTenantTwoIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String LEDGER = "golden/tenant-two-ledger.csv";
    private static final String RECOGNITION = "golden/tenant-two-recognition.csv";

    /** The anonymised property; the three template-derived account names embed it. */
    private static final String PROPERTY = "Sample Residences";
    private static final String RENTER = "Sample Renter Two";
    private static final String CONTRACT_REF = "SAMPLE-26/001";

    private static final String RENT_RECEIVABLE = "Rent Receivable - " + PROPERTY;
    private static final String ADVANCE_RENT = "Advance Rent - " + PROPERTY;
    private static final String RENTAL_INCOME = "Rental Income " + PROPERTY;

    /** The two accounts created by hand and mapped onto the property's roles (P5-R19). */
    private static final String PDC_RECEIVABLE = "Shared PDC Receivable EIB";
    private static final String ADMIN_FEE = "Shared Admin Fee";

    /** What the template would have given those two roles, and did until the remap. */
    private static final String TEMPLATE_PDC_RECEIVABLE = "PDC Receivable " + PROPERTY;
    private static final String TEMPLATE_ADMIN_FEE = "Admin Fee - " + PROPERTY;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate TERM_START = LocalDate.of(2026, 9, 24);
    private static final LocalDate TERM_END = LocalDate.of(2027, 9, 23);
    private static final LocalDate CUT_OFF = LocalDate.of(2027, 9, 30);

    /** Wide enough to hold the whole tenancy; the ledger query is inclusive of both ends. */
    private static final LocalDate LEDGER_FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate LEDGER_TO = LocalDate.of(2028, 12, 31);

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PropertyService properties;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired ChargeTypeService chargeTypes;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseService leases;
    @Autowired ChequeGenerationService grid;
    @Autowired LeasePostingService posting;
    @Autowired RecognitionService recognition;
    @Autowired LedgerQueryService ledger;

    UUID propertyId;
    UUID renterId;
    UUID leaseId;

    /** The template's own leaves for the two remapped roles, captured before the remap. */
    UUID templatePdcAccountId;
    String templatePdcAccountName;
    UUID templateAdminFeeAccountId;
    String templateAdminFeeAccountName;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures ground = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                properties, accounts, propertyAccounts, chargeTypes);
        ground.newTenant();
        // LeaseAccessPolicy fails closed: without an authentication every read of
        // the lease answers "Lease not found". See LeaseTestFixtures' own note.
        LeaseTestFixtures.authenticateAsTenantAdmin();
        ground.seedAccounting();

        // The first cheque is registered on 2026-09-11, five days before the
        // contract date, so the books must be open before then — not before the
        // contract, which is the easy mistake this contract punishes.
        fiscal.setBooksStartDate(LocalDate.of(2026, 9, 1));
        fiscal.lockThrough(LocalDate.of(2026, 8, 31));

        // Through PropertyService, never the repository: creating a property is what
        // runs generateMissing and gives it the leaf accounts the fixture names.
        Property p = new Property();
        p.setNameEn(PROPERTY);
        p.setEmirate(Emirate.ABU_DHABI);
        p.setCode("SR_" + UUID.randomUUID().toString().substring(0, 8));
        p.setTenantId(ground.tenantId());
        Property property = properties.createProperty(p);
        propertyId = property.getId();

        // Ruling P5-R19 (spec D2). Two of the five roles are taken off the leaves
        // the template just generated and pointed at accounts created by hand.
        // Capture the generated ones first: the point of the proof is that they
        // stay empty afterwards.
        Account templatePdc = resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId);
        templatePdcAccountId = templatePdc.getId();
        templatePdcAccountName = templatePdc.getName();
        Account templateAdminFee = resolver.resolve(AccountRole.ADMIN_FEE, propertyId);
        templateAdminFeeAccountId = templateAdminFee.getId();
        templateAdminFeeAccountName = templateAdminFee.getName();

        // Under the template's own parents, so the leaf carries the account type
        // setMapping demands for the role (it refuses a type mismatch outright).
        remap(AccountRole.PDC_RECEIVABLE, PDC_RECEIVABLE, "A-02-03");
        remap(AccountRole.ADMIN_FEE, ADMIN_FEE, "C-01-01");

        Unit unit = ground.createUnit(property, "B2-002");
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
                line("RENT", "51000.00"),
                line("ADMIN_FEE", "2000.00")));
        leaseId = leases.createDraftLease(dto).getId();

        // The export's contract string. CreateLeaseDTO carries no field for it and
        // Lease.contractNumber is a server-assigned number, so it goes where plan 4
        // put the source system's references: leases.external_contract_ref (P5-R3).
        leaseRepo.findById(leaseId).ifPresent(l -> {
            l.setExternalContractRef(CONTRACT_REF);
            leaseRepo.save(l);
        });

        // Five post-dated cheques, each registered on its own posting date rather
        // than all on the contract date — the admin fee before the contract is even
        // dated. Σ = 53,000.00 = the contract value, which post() requires.
        grid.saveRows(leaseId, List.of(
                cheque(1, "000201", LocalDate.of(2026, 9, 11), "2000.00", "Admin Fees"),
                cheque(2, "000202", LocalDate.of(2026, 10, 2), "12750.00", "Rent - 1st Installment"),
                cheque(3, "000203", LocalDate.of(2027, 1, 2), "12750.00", "Rent - 2nd Installment"),
                cheque(4, "000204", LocalDate.of(2027, 4, 2), "12750.00", "Rent - 3rd Installment"),
                cheque(5, "000205", LocalDate.of(2027, 7, 2), "12750.00", "Rent - 4th Installment")));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /**
     * Create a leaf under the template role's own parent group and map the role
     * onto it, replacing the generated mapping — the service method behind
     * {@code PUT /properties/{id}/accounts/{role}}.
     */
    private void remap(AccountRole role, String name, String parentCode) {
        Account leaf = accounts.createLeaf(name, accounts.getAccountByCode(parentCode), null);
        propertyAccounts.setMapping(propertyId, role, leaf.getId());
        assertThat(resolver.resolve(role, propertyId).getName())
                .as("role %s must resolve to the manually mapped account", role)
                .isEqualTo(name);
    }

    /**
     * One grid row. The posting date and the date written on the instrument are the
     * same day here — the landlord took each cheque in on the day it is dated —
     * and the {@code PDR} follows the posting date, not the contract date.
     */
    private static ChequeRowInput cheque(int seq, String number, LocalDate date,
                                         String amount, String narration) {
        return new ChequeRowInput(null, seq, date, number, date,
                "Emirates Islamic", null, null, new BigDecimal(amount), narration, ChequeMode.PDC);
    }

    /** The contract's whole life: posted, registered, recognised. Nothing is ever banked. */
    private void replay() {
        posting.post(leaseId);

        assertThat(grid.list(leaseId)).hasSize(5);

        RecognitionRunResult run = recognition.runTo(CUT_OFF, false);
        // runTo swallows a per-entry failure into errors() and carries on; without
        // this a refused CIL would read as a ledger mismatch three tests later
        // rather than as "entry X could not be posted: <cause>".
        assertThat(run.errors()).as("every recognition entry posted").isEmpty();
        assertThat(run.posted()).isEqualTo(13);
    }

    /** The renter's ledger, one entry per account, keyed by the name the export prints. */
    private Map<String, AccountLedgerDTO> renterLedgerByAccount() {
        return ledger.renterLedger(renterId, LEDGER_FROM, LEDGER_TO).stream()
                .collect(Collectors.toMap(AccountLedgerDTO::accountName, Function.identity(),
                        (a, b) -> { throw new IllegalStateException("duplicate account " + a.accountName()); },
                        LinkedHashMap::new));
    }

    private AccountLedgerDTO ledgerOf(UUID accountId) {
        return ledger.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(LEDGER_FROM, LEDGER_TO, null, null, null, null));
    }

    // ------------------------------------------------------------------
    // the gate
    // ------------------------------------------------------------------

    /**
     * Three of the five account names are the template's for this property; the
     * other two are the manual ones the remap put there.
     *
     * <p>This is where the fixture's names stop being a claim: if a template
     * pattern is edited, or if a remap silently fails and the role falls back to
     * the generated leaf, this fails and names the role instead of the whole
     * replay failing on row 1 of an account the product no longer has.</p>
     */
    @Test
    void theAccountNamesAreTheTemplatesExceptTheTwoRemappedRoles() {
        assertThat(resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId).getName()).isEqualTo(RENT_RECEIVABLE);
        assertThat(resolver.resolve(AccountRole.ADVANCE_RENT, propertyId).getName()).isEqualTo(ADVANCE_RENT);
        assertThat(resolver.resolve(AccountRole.RENTAL_INCOME, propertyId).getName()).isEqualTo(RENTAL_INCOME);
        assertThat(resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId).getName()).isEqualTo(PDC_RECEIVABLE);
        assertThat(resolver.resolve(AccountRole.ADMIN_FEE, propertyId).getName()).isEqualTo(ADMIN_FEE);

        // The remap is a real change of account, not a rename of the same leaf:
        // the template's own names for those two roles are these, and the fixture
        // names neither of them.
        assertThat(templatePdcAccountName).isEqualTo(TEMPLATE_PDC_RECEIVABLE);
        assertThat(templateAdminFeeAccountName).isEqualTo(TEMPLATE_ADMIN_FEE);

        assertThat(GoldenLedgerFixture.ledgerByAccount(LEDGER).keySet())
                .as("the fixture is written in three template names and two manual ones")
                .containsExactlyInAnyOrder(RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME,
                        PDC_RECEIVABLE, ADMIN_FEE);

        assertThat(leaseRepo.findById(leaseId).orElseThrow().getExternalContractRef())
                .isEqualTo(CONTRACT_REF);
    }

    @Test
    void everyAccountMatchesTheExportLineForLine() {
        replay();

        Map<String, List<GoldenRow>> expected = GoldenLedgerFixture.ledgerByAccount(LEDGER);
        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();

        // Exactly, not "contains": a sixth account — output VAT on a charge type
        // whose default flipped, a penalty the rule engine decided to post, or the
        // template leaf the remap was supposed to take out of the picture — would
        // otherwise never be compared against anything (ruling P5-R14).
        assertThat(actual.keySet())
                .as("the renter's ledger must touch exactly the accounts the export does")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach((account, rows) ->
                LedgerDiff.assertAccountMatches(account, rows, actual.get(account)));
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
        assertThat(debit).isEqualByComparingTo("157000.00");
        assertThat(credit).isEqualByComparingTo(debit);

        // And the fixture's own columns, so a transcription slip reads as a bad
        // fixture rather than as a product failure.
        List<GoldenRow> fixture = GoldenLedgerFixture.ledger(LEDGER);
        assertThat(GoldenLedgerFixture.reportTotal(fixture)).isEqualByComparingTo("157000.00");
        assertThat(GoldenLedgerFixture.creditTotal(fixture)).isEqualByComparingTo("157000.00");
    }

    /**
     * Ruling P5-R19 / spec D2, in both directions: the two manually mapped accounts
     * received every line of their role, and the leaves the template generated for
     * those same two roles received nothing at all.
     *
     * <p>The second half is the one that matters. Without it a remap that silently
     * did not take would still pass the line-for-line diff on three accounts and
     * fail somewhere unreadable on the other two.</p>
     */
    @Test
    void theTwoRemappedRolesPostToTheManualAccountsAndLeaveTheTemplateLeavesEmpty() {
        replay();

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();

        // Named before they are dereferenced: a remap that did not take reads as
        // "the ledger has no such account", not as a null pointer three lines down.
        assertThat(actual)
                .as("both remapped roles must have posted to their manual accounts")
                .containsKeys(PDC_RECEIVABLE, ADMIN_FEE);

        // The manual PDC account carries all five registrations; the manual admin
        // fee account carries the contract's own credit.
        assertThat(actual.get(PDC_RECEIVABLE).rows()).hasSize(5);
        assertThat(actual.get(PDC_RECEIVABLE).totalDebit()).isEqualByComparingTo("53000.00");
        assertThat(actual.get(PDC_RECEIVABLE).closingBalance()).isEqualByComparingTo("53000.00");
        assertThat(actual.get(ADMIN_FEE).rows()).hasSize(1);
        assertThat(actual.get(ADMIN_FEE).totalCredit()).isEqualByComparingTo("2000.00");

        for (UUID generated : List.of(templatePdcAccountId, templateAdminFeeAccountId)) {
            AccountLedgerDTO untouched = ledgerOf(generated);
            assertThat(untouched.rows())
                    .as("the template leaf \"%s\" must not receive a line once the role is remapped",
                            untouched.accountName())
                    .isEmpty();
            assertThat(untouched.totalDebit()).isEqualByComparingTo("0.00");
            assertThat(untouched.totalCredit()).isEqualByComparingTo("0.00");
            assertThat(untouched.closingBalance()).isEqualByComparingTo("0.00");
        }
    }

    /**
     * Deviation D-d, asserted where a reader will find it. The admin-fee cheque was
     * registered on 2026-09-11 and the contract is dated 2026-09-16, so our
     * receivable opens with that {@code PDR} and sits at −2,000.00 until the
     * contract itself is raised. The export prints the same row last, inside its
     * post-dated sub-block; the row set and the closing balance are the same.
     */
    @Test
    void theReceivableOpensWithThePdrDatedBeforeTheContract() {
        replay();

        List<LedgerRowDTO> rows = renterLedgerByAccount().get(RENT_RECEIVABLE).rows();

        assertThat(rows).hasSize(7);
        assertThat(rows.get(0).entryDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(rows.get(0).docType()).isEqualTo("PDR");
        assertThat(rows.get(0).credit()).isEqualByComparingTo("2000.00");
        assertThat(rows.get(0).balance()).isEqualByComparingTo("-2000.00");
        assertThat(rows.get(1).entryDate()).isEqualTo(CONTRACT_DATE);
        assertThat(rows.get(1).docType()).isEqualTo("TCO");
    }

    @Test
    void theTrialBalanceBalancesAndLeavesTheWholeContractInPdcReceivable() {
        replay();

        // Tenant-wide on purpose: a property-filtered trial balance cannot see a
        // line posted without the property dimension (Task 3 review I-1).
        List<TrialBalanceRowDTO> tb = ledger.trialBalance(CUT_OFF, null);
        // Five, so a sixth account carrying a posted line — one without the renter
        // dimension, which the renter ledger cannot see at all — fails here.
        assertThat(tb).hasSize(5);

        BigDecimal debit = tb.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = tb.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).isEqualByComparingTo(credit);

        Map<String, BigDecimal> balance = tb.stream()
                .collect(Collectors.toMap(TrialBalanceRowDTO::name, TrialBalanceRowDTO::balance));
        assertThat(balance)
                .as("the trial balance must name the five accounts the export does")
                .containsOnlyKeys(RENT_RECEIVABLE, ADVANCE_RENT, PDC_RECEIVABLE, RENTAL_INCOME, ADMIN_FEE);
        // Nothing was ever banked, so the whole contract is still in PDC receivable.
        assertThat(balance.get(RENT_RECEIVABLE)).isEqualByComparingTo("0.00");
        assertThat(balance.get(ADVANCE_RENT)).isEqualByComparingTo("0.00");
        assertThat(balance.get(PDC_RECEIVABLE)).isEqualByComparingTo("53000.00");
        assertThat(balance.get(RENTAL_INCOME)).isEqualByComparingTo("-51000.00");
        assertThat(balance.get(ADMIN_FEE)).isEqualByComparingTo("-2000.00");
    }

    @Test
    void theRecognitionScheduleMatchesTheSpecsOwnReferenceTable() {
        replay();

        List<LedgerDiff.RecognitionRow> got = recognition.scheduleFor(leaseId).stream()
                .map(e -> new LedgerDiff.RecognitionRow(e.periodStart(), e.periodEnd(), e.days(), e.amount()))
                .toList();

        LedgerDiff.assertRecognitionMatches(GoldenLedgerFixture.recognition(RECOGNITION), got);

        // The four figures the spec's §8.2 table prints, checked individually so a
        // failure names which one moved rather than "the list differs".
        assertThat(got.get(0).amount()).isEqualByComparingTo("978.08");    // 24-30 Sep 2026, 7 days
        assertThat(got.get(1).amount()).isEqualByComparingTo("4331.51");   // Oct 2026, 31 days
        assertThat(got.get(2).amount()).isEqualByComparingTo("4191.78");   // Nov 2026, 30 days
        assertThat(got.get(12).amount()).isEqualByComparingTo("3213.68");  // 1-23 Sep 2027, 23 days

        // 51,000 over 365 days, the remainder in the last slice — never a 366th day
        // of rent, and never a 366th day of term.
        assertThat(got.stream().map(LedgerDiff.RecognitionRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000.00");
        assertThat(got.stream().mapToInt(LedgerDiff.RecognitionRow::days).sum()).isEqualTo(365);
        assertThat(recognition.scheduleFor(leaseId)).extracting(RecognitionEntryDTO::status)
                .allMatch(s -> "POSTED".equals(s.name()));
    }

    @Test
    void thePostedContractsRenterLedgerNetsToZeroBeforeAnyRecognition() {
        // Spec §6.4: "After posting the renter's ledger nets to zero, as in PACT."
        // Here that survives the whole contract — nothing ever clears — but it is
        // still asserted at the moment the spec means it.
        posting.post(leaseId);

        Map<String, AccountLedgerDTO> actual = renterLedgerByAccount();
        assertThat(actual.get(RENT_RECEIVABLE).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(actual.get(PDC_RECEIVABLE).closingBalance()).isEqualByComparingTo("53000.00");
    }
}
