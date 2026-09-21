package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.SettlementService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the cheque register may still do once the contract has ended — and the one
 * thing that finishes the contract off.
 *
 * <p><b>Money owed stays collectable.</b> Spec §9.1 keeps every uncleared
 * instrument dated on or before {@code T} "for collection", and §9.2 raises a
 * register row for a settlement balance the deposit could not cover. Both are
 * instruments against a debt the renter genuinely owes, and both live on a lease
 * that is TERMINATED — so the register's transitions (deposit, clear, receive,
 * bounce, replace, return, cancel, the gateway's three) admit ACTIVE,
 * NOTICE_GIVEN, EXPIRED, RENEWED <em>and</em> TERMINATED. What stays live-lease
 * only is <em>shaping</em> the grid: a contract that has ended does not grow new
 * instalments.</p>
 *
 * <p><b>CLOSED is terminal.</b> Once the settlement is finalised and the last
 * outstanding row has resolved, the lease closes and the register is shut: every
 * transition is refused. The single exception is a gateway webhook retried
 * against a row that already captured, which must go on answering idempotently
 * however long Razorpay keeps trying.</p>
 *
 * <p><b>The fixture</b> is {@code SettlementServiceIT}'s Galah lease — 51,000 of
 * rent over 24 Sep 2026 → 23 Sep 2027, a 2,000 admin fee and a 3,000 security
 * deposit, paid by six instruments — with one deliberate difference: the
 * <b>2 Jan 2027 rent cheque is left uncleared</b>. It is dated before the 15 Feb
 * termination, so §9.1's default split <em>keeps</em> it, and it is the kept
 * instrument these tests bank.</p>
 *
 * <table>
 *   <tr><th>figure</th><th>value</th><th>from</th></tr>
 *   <tr><td>PDC receivable after termination</td><td>12,750.00</td>
 *       <td>56,000 registered − 17,750 cleared − 25,500 handed back</td></tr>
 *   <tr><td>rent receivable after termination</td><td>−5,239.73</td>
 *       <td>56,000 − 56,000 + 25,500 handed back − 30,739.73 unearned</td></tr>
 *   <tr><td>net refund</td><td>8,239.73</td><td>3,000 held − (−5,239.73)</td></tr>
 * </table>
 *
 * <p>Clearing does not move the receivable — a {@code CRT} is Dr bank / Cr PDC
 * receivable — so the settlement's figures are the same whether the January
 * cheque cleared or was kept. Only {@code receivedTotal} differs (17,750 here
 * against 30,500 there), and nothing is computed from it.</p>
 */
@SpringBootTest
@Testcontainers
class ChequeOnEndedLeaseIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ChequeService cheques;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseTerminationService termination;
    @Autowired SettlementService settlement;
    @Autowired RecognitionService recognition;
    @Autowired LeasePostingService posting;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseEventRepository leaseEvents;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    private static final LocalDate ADMIN_CHEQUE = LocalDate.of(2026, 9, 11);
    private static final LocalDate DEPOSIT_CHEQUE = LocalDate.of(2026, 9, 12);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    /** The kept one: uncleared and dated before T, so §9.1 hands it to collection. */
    private static final LocalDate RENT_2 = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENT_3 = LocalDate.of(2027, 4, 2);
    private static final LocalDate RENT_4 = LocalDate.of(2027, 7, 2);

    private static final LocalDate RECOGNISED_TO = LocalDate.of(2027, 1, 31);
    private static final LocalDate T = LocalDate.of(2027, 2, 15);
    private static final LocalDate SETTLED_ON = LocalDate.of(2027, 2, 20);
    /** The day the kept paper is finally banked, a fortnight after the move-out. */
    private static final LocalDate BANKED_ON = LocalDate.of(2027, 3, 1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /**
     * The lease on the books with six instruments registered; the admin fee, the
     * deposit and the October rent clear on their own cheque dates — so nothing is
     * ever late — and the January rent is left in the drawer.
     */
    private UUID galahWithOneChequeStillInTheDrawer() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"),
                        line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100045", DEPOSIT_CHEQUE, DEPOSIT_CHEQUE, "3000"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, DEPOSIT_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        return leaseId;
    }

    /** …recognised to 31 Jan, terminated on 15 Feb, recognised again to 15 Feb. */
    private UUID terminatedWithAKeptCheque() {
        UUID leaseId = galahWithOneChequeStillInTheDrawer();
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, "Renter relocating"), null);
        recognition.runTo(T, false);
        return leaseId;
    }

    /** The same lease with every instrument resolved — the four cleared, the two dated after T handed back. */
    private UUID terminatedWithNothingOutstanding() {
        UUID leaseId = galahWithOneChequeStillInTheDrawer();
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);
        recognition.runTo(T, false);
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private void clearOnItsOwnDate(Cheque cheque) {
        cheques.deposit(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
        cheques.clear(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return register(leaseId).stream().filter(c -> chequeDate.equals(c.getChequeDate()))
                .findFirst().orElseThrow(() -> new AssertionError("No cheque dated " + chequeDate));
    }

    private Cheque chequeById(UUID id) {
        return tx.execute(s -> chequeRepo.findById(id).orElseThrow());
    }

    private ChequeStatus statusOf(UUID chequeId) {
        return chequeById(chequeId).getStatus();
    }

    private Lease lease(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private LeaseStatus statusOfLease(UUID leaseId) {
        return lease(leaseId).getStatus();
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    /** An account's closing balance on this lease alone; a credit balance reads negative. */
    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        UUID accountId = leaf(role).getId();
        return tx.execute(s -> ledger.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long closedEvents(UUID leaseId) {
        return tx.execute(s -> leaseEvents.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .filter(e -> e.getNewState() == LeaseStatus.CLOSED).count());
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    private SettlementResponseDTO finalizeSettlement(UUID leaseId, UUID bankAccountId,
                                                     SaveSettlementDTO.DeductionItemDTO... lines) {
        SaveSettlementDTO draft = new SaveSettlementDTO();
        draft.setDeductions(List.of(lines));
        settlement.saveDraft(leaseId, draft, null);
        return settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, bankAccountId), null);
    }

    private static SaveSettlementDTO.DeductionItemDTO deduction(DeductionCategory category, String amount) {
        SaveSettlementDTO.DeductionItemDTO item = new SaveSettlementDTO.DeductionItemDTO();
        item.setType(LineItemType.DEDUCTION);
        item.setCategory(category);
        item.setDescription(category.name().toLowerCase().replace('_', ' '));
        item.setAmount(new BigDecimal(amount));
        return item;
    }

    // ------------------------------------------------------------------
    // (a) the kept instrument is banked, and the lease closes when it clears
    // ------------------------------------------------------------------

    /**
     * §9.1's keep list, followed all the way through: the January cheque stays on
     * the register through the termination, the settlement is finalised while it is
     * still outstanding — so the contract is <em>not</em> finished — and banking it
     * a fortnight later is what closes the lease.
     *
     * <p>The {@code CRT} is the ordinary one: Dr bank / Cr PDC receivable, which
     * takes the last 12,750 off the instruments the landlord is holding and leaves
     * nothing behind on either dimension.</p>
     */
    @Test
    void aKeptChequeIsDepositedAndClearedAndTheLastClearanceClosesTheLease() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        assertThat(statusOf(kept)).as("kept, not handed back").isEqualTo(ChequeStatus.REGISTERED);
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId))
                .as("the one instrument still in the drawer").isEqualByComparingTo("12750.00");

        SettlementResponseDTO settled = finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(settled.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(settled.getRefundAmount()).as("3,000 held + 5,239.73 owed").isEqualByComparingTo("8239.73");
        assertThat(statusOfLease(leaseId))
                .as("a finalised settlement does not close a lease that is still holding paper")
                .isEqualTo(LeaseStatus.TERMINATED);

        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(statusOfLease(leaseId)).as("banking is not collecting").isEqualTo(LeaseStatus.TERMINATED);

        cheques.clear(kept, ChequeActionRequest.on(BANKED_ON));

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(chequeById(kept).getCrtJournalId()).as("the CRT").isNotNull();
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId))
                .as("nothing left in the drawer").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the STL flattened it and a CRT does not move it").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(statusOfLease(leaseId)).as("the last clearance closes the contract")
                .isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).as("one event, not two").isEqualTo(1);
        assertTrialBalanceBalances();
    }

    /**
     * Collecting everything is not the same as settling: a terminated lease whose
     * register is empty but whose settlement nobody has finalised stays open.
     *
     * <p>The deposit is still on the books and the deductions have not been agreed,
     * so the contract is not finished with — and closing it would shut the door on
     * the settlement screen that has to do exactly that. When the settlement is
     * finalised a moment later, <em>that</em> is the last of the three conditions,
     * and the same rule closes the lease from the other side.</p>
     */
    @Test
    void collectingEverythingBeforeTheSettlementIsFinalisedDoesNotCloseTheLease() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();

        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.clear(kept, ChequeActionRequest.on(BANKED_ON));

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(statusOfLease(leaseId))
                .as("the deposit has not been accounted for yet")
                .isEqualTo(LeaseStatus.TERMINATED);
        assertThat(closedEvents(leaseId)).isZero();

        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());

        assertThat(statusOfLease(leaseId)).as("finalising is the last condition to arrive")
                .isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // (b) the settlement's balance-due row is collectable
    // ------------------------------------------------------------------

    /**
     * §9.2's other half: the deductions swallow the deposit, the renter is left
     * owing 1,760.27, and the CASH row the settlement raises for it can actually be
     * received — which is what closes the lease.
     */
    @Test
    void theSettlementBalanceDueRowIsReceivedAndClosesTheLease() {
        UUID leaseId = terminatedWithNothingOutstanding();

        SettlementResponseDTO settled = finalizeSettlement(leaseId, null,
                deduction(DeductionCategory.PROPERTY_DAMAGE, "10000"));

        assertThat(settled.getBalanceDue()).as("3,000 + 5,239.73 − 10,000").isEqualByComparingTo("1760.27");
        UUID collection = settled.getCollectionChequeId();
        assertThat(collection).as("the row raised to collect it").isNotNull();
        assertThat(statusOfLease(leaseId)).as("money is still owed").isEqualTo(LeaseStatus.TERMINATED);
        assertThat(chequeById(collection).getMode()).isEqualTo(ChequeMode.CASH);

        cheques.receive(collection, ChequeActionRequest.on(SETTLED_ON));

        assertThat(statusOf(collection)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("nothing left owing on the contract").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // (c) a bounced row is money owed, and it blocks the close
    // ------------------------------------------------------------------

    /**
     * A cheque that failed is not "resolved" — it is the debt the renter most
     * urgently owes — so a BOUNCED row that nothing has replaced keeps the contract
     * open however much else has been collected.
     *
     * <p>The sequence is the real one: the kept January cheque is banked and comes
     * back, the settlement is finalised against the receivable that leaves, the
     * balance due is collected in cash — and the lease is <em>still</em> TERMINATED,
     * because 12,750 of returned paper is still outstanding. Only once the renter
     * replaces it and the replacement clears does the contract close.</p>
     *
     * <p>It is also the proof that the penalty hooks survive a terminated lease:
     * {@code bounce} calls {@code PenaltyRuleEngine.onBounce} inside its own
     * transaction, and a rule that refused to propose on an ended contract would
     * roll the bounce back with it.</p>
     */
    @Test
    void aBouncedRowNothingHasReplacedKeepsTheLeaseOpen() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();

        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(chequeById(kept).getCbrJournalId()).as("the CBR").isNotNull();
        // The CBR put the 12,750 back on the receivable: −5,239.73 + 12,750.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("7510.27");

        SettlementResponseDTO settled = finalizeSettlement(leaseId, null);
        assertThat(settled.getBalanceDue()).as("7,510.27 owed − 3,000 held").isEqualByComparingTo("4510.27");
        UUID collection = settled.getCollectionChequeId();
        assertThat(collection).isNotNull();

        cheques.receive(collection, ChequeActionRequest.on(SETTLED_ON));
        assertThat(statusOfLease(leaseId))
                .as("a bounced cheque nobody has replaced is money owed")
                .isEqualTo(LeaseStatus.TERMINATED);

        List<ChequeDTO> replacements = cheques.replace(kept, new ReplaceChequeRequest(
                List.of(row("100055", BANKED_ON, BANKED_ON, "12750")), BANKED_ON, "Renter paid by new cheque"));
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.REPLACED);
        assertThat(statusOfLease(leaseId)).as("the replacement is itself outstanding")
                .isEqualTo(LeaseStatus.TERMINATED);

        UUID replacement = replacements.get(0).id();
        cheques.deposit(replacement, ChequeActionRequest.on(BANKED_ON));
        cheques.clear(replacement, ChequeActionRequest.on(BANKED_ON));

        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // (d) CLOSED is terminal
    // ------------------------------------------------------------------

    /**
     * Every register transition is refused on a closed contract, by name.
     *
     * <p>The guard sits above each verb's own status rule, so the refusal a clerk
     * sees names the <em>lease</em> rather than telling them the row is in the
     * wrong state — which on a closed lease would be the wrong answer to the wrong
     * question.</p>
     */
    @Test
    void everyTransitionIsRefusedOnAClosedLease() {
        UUID leaseId = terminatedWithNothingOutstanding();
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(statusOfLease(leaseId)).as("nothing outstanding at finalise").isEqualTo(LeaseStatus.CLOSED);

        UUID cleared = chequeOn(leaseId, RENT_1).getId();
        UUID returned = chequeOn(leaseId, RENT_3).getId();

        assertClosedRefusal(() -> cheques.deposit(returned, ChequeActionRequest.empty()));
        assertClosedRefusal(() -> cheques.clear(returned, ChequeActionRequest.empty()));
        assertClosedRefusal(() -> cheques.receive(returned, ChequeActionRequest.empty()));
        assertClosedRefusal(() -> cheques.bounce(cleared, ChequeActionRequest.empty()));
        assertClosedRefusal(() -> cheques.cancel(returned, ChequeActionRequest.empty()));
        assertClosedRefusal(() -> cheques.returnToTenant(returned, null, null));
        assertClosedRefusal(() -> cheques.replace(cleared, new ReplaceChequeRequest(
                List.of(row("100060", BANKED_ON, BANKED_ON, "100")), BANKED_ON, null)));
        assertClosedRefusal(() -> cheques.replaceForOnlinePayment(cleared, BANKED_ON));
        assertClosedRefusal(() -> cheques.registerOnlinePending(returned));
        assertClosedRefusal(() -> cheques.revertOnlinePending(returned));
        assertClosedRefusal(() -> cheques.clearOnline(returned, BANKED_ON, null));
        assertClosedRefusal(() -> cheques.addRowToPostedLease(leaseId,
                row(null, BANKED_ON, BANKED_ON, "500")));
        assertClosedRefusal(() -> cheques.cashReceipt(leaseId,
                new ChequeRowInput(null, null, BANKED_ON, null, BANKED_ON, null, null, null,
                        new BigDecimal("500"), "Cash", ChequeMode.CASH)));
        assertClosedRefusal(() -> cheques.depositBatch(new DepositBatchRequest(List.of(returned), BANKED_ON, null)));

        // Nothing moved.
        assertThat(statusOf(cleared)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(statusOf(returned)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(register(leaseId)).as("no row was added").hasSize(6);
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertTrialBalanceBalances();
    }

    private void assertClosedRefusal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("CLOSED");
    }

    // ------------------------------------------------------------------
    // (e) shaping the grid is still a live lease's privilege
    // ------------------------------------------------------------------

    /**
     * A terminated contract does not grow new instalments — the settlement's own
     * door is the single exception, and it is not this one.
     *
     * <p>An EXPIRED lease is deliberately <em>not</em> in the same boat: its grid
     * has always been open (a tenancy that simply ran out still takes a counter
     * receipt for the last month, and an approved penalty on it raises its
     * collection row through this very method). Nothing here changes that.</p>
     */
    @Test
    void aTerminatedLeaseTakesNoNewGridRows() {
        UUID leaseId = terminatedWithAKeptCheque();

        assertThatThrownBy(() -> cheques.addRowToPostedLease(leaseId, row(null, BANKED_ON, BANKED_ON, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This lease is TERMINATED")
                .hasMessageContaining("cheque grid");
        assertThatThrownBy(() -> cheques.cashReceipt(leaseId,
                new ChequeRowInput(null, null, BANKED_ON, null, BANKED_ON, null, null, null,
                        new BigDecimal("500"), "Cash at the counter", ChequeMode.CASH)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This lease is TERMINATED");
        assertThatThrownBy(() -> chequeGeneration.saveRows(leaseId, List.of(row(null, BANKED_ON, BANKED_ON, "500"))))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(register(leaseId)).as("six rows, as the contract had").hasSize(6);
    }

    // ------------------------------------------------------------------
    // the gateway's last capture, and its retry
    // ------------------------------------------------------------------

    /**
     * The renter settles the last outstanding row through Razorpay, which closes the
     * lease — and the webhook's retry, arriving on a contract that is now CLOSED,
     * still answers idempotently rather than erroring forever.
     *
     * <p>That exception is deliberate and narrow: an {@code ONLINE} row that has
     * already captured is the one thing a closed lease still answers for, because
     * the alternative is a gateway retrying a 400 until it gives up and a payment
     * that reconciliation cannot explain.</p>
     */
    @Test
    void theGatewayCapturesTheLastRowAndItsRetryStaysIdempotent() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));

        ChequeDTO online = cheques.replaceForOnlinePayment(kept, BANKED_ON);
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(statusOfLease(leaseId)).as("the online row is outstanding").isEqualTo(LeaseStatus.TERMINATED);

        cheques.registerOnlinePending(online.id());
        assertThat(statusOf(online.id())).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(statusOfLease(leaseId)).as("an authorisation is not money").isEqualTo(LeaseStatus.TERMINATED);

        cheques.clearOnline(online.id(), BANKED_ON, null);

        assertThat(statusOf(online.id())).isEqualTo(ChequeStatus.CLEARED);
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);

        ChequeDTO retry = cheques.clearOnline(online.id(), BANKED_ON, null);
        assertThat(retry.status()).as("the webhook's retry is still idempotent").isEqualTo(ChequeStatus.CLEARED);
        assertThat(closedEvents(leaseId)).as("and it does not close the lease a second time").isEqualTo(1);
        assertTrialBalanceBalances();
    }
}
