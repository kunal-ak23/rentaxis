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
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
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
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
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
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

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
class ChequeOnEndedLeaseIT extends AbstractPostgresIT {

    @Autowired ChequeService cheques;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseTerminationService termination;
    @Autowired SettlementService settlement;
    @Autowired PenaltyAssessmentService penalties;
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
    /** A cheque that had cleared comes back before the statement is drawn (#297). */
    private static final LocalDate RETURNED_BEFORE_SETTLEMENT = LocalDate.of(2027, 2, 18);
    /** A fine approved before T, so its collection row is kept rather than handed back. */
    private static final LocalDate PENALTY_ON = LocalDate.of(2027, 1, 20);

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

    /**
     * What CLOSED now <em>means</em>, asserted wherever a test claims it.
     *
     * <p>Closure is a question about the ledger, not about the register's statuses
     * (review C-1/I-2): a contract is finished with when nothing is owed on it,
     * nothing is still on paper, and no deposit is still being held. Every closure
     * test in this class ends here, so a rule that closed a lease over a live
     * balance would fail somewhere rather than only in the one test that thought to
     * look.</p>
     */
    private void assertNothingLeftOnTheLease(UUID leaseId) {
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("rent receivable on a closed lease").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId))
                .as("PDC receivable on a closed lease").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId))
                .as("deposits held on a closed lease").isEqualByComparingTo("0.00");
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
        // acknowledgeOutstanding: every lease in this class is finalised with paper
        // still on its register — that is the whole subject — and a refund in that
        // state now needs the accountant to say so on purpose (review I1).
        return settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, bankAccountId, true), null);
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
        assertNothingLeftOnTheLease(leaseId);
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
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // every way the last outstanding row can leave the set
    // ------------------------------------------------------------------

    /**
     * A cheque the settlement was drawn counting on cannot be handed back or
     * cancelled once that settlement is FINALIZED (review I-2).
     *
     * <p>Both verbs reverse the row's {@code PDR}, which re-debits the rent
     * receivable. On a running contract that is right — the renter owes the money
     * again. On a settled one it is not: the statement already netted this
     * instrument's 12,750 against the deposit and paid a refund out on that basis,
     * so putting it back leaves a CLOSED-eligible contract owing 12,750 that no
     * door can collect — which is precisely how lifecycle row 12 used to end.</p>
     *
     * <p>The refusal names the way out: {@code replace} is still allowed, because
     * paper for paper leaves the receivable exactly where the settlement left
     * it — see {@link #aKeptChequeThatBouncesAfterFinaliseIsReplacedAndClosesTheLease}.</p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("waysFinanceMightUndoAKeptCheque")
    void aKeptChequeCannotBeUndoneOnceTheSettlementIsFinalised(
            String name, BiConsumer<ChequeOnEndedLeaseIT, UUID> transition) {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(statusOfLease(leaseId)).as("one instrument still outstanding")
                .isEqualTo(LeaseStatus.TERMINATED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the STL flattened the receivable").isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> transition.accept(this, kept))
                .as(name)
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The settlement was finalised counting on this cheque");

        assertThat(statusOf(kept)).as("the row after a refused " + name).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("nothing was put back on a settled receivable").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("12750.00");
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(closedEvents(leaseId)).isZero();
        assertTrialBalanceBalances();
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
            waysFinanceMightUndoAKeptCheque() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("handed back to the tenant",
                        (BiConsumer<ChequeOnEndedLeaseIT, UUID>) (it, id) ->
                                it.cheques.returnToTenant(id, BANKED_ON, "Renter collected the cheque")),
                org.junit.jupiter.params.provider.Arguments.of("cancelled by finance",
                        (BiConsumer<ChequeOnEndedLeaseIT, UUID>) (it, id) ->
                                it.cheques.cancel(id, new ChequeActionRequest(
                                        BANKED_ON, "Written off at settlement", null, null))));
    }

    /**
     * …and the same two verbs are untouched before the settlement is finalised: the
     * refusal is about a statement that has already been drawn, not about the
     * register on a terminated lease.
     */
    @Test
    void aKeptChequeIsStillHandedBackFreelyWhileTheSettlementIsOnlyADraft() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        SaveSettlementDTO draft = new SaveSettlementDTO();
        draft.setDeductions(List.of());
        settlement.saveDraft(leaseId, draft, null);

        cheques.returnToTenant(kept, BANKED_ON, "Renter collected the cheque");

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the reversal put the instalment back on the renter's account")
                .isEqualByComparingTo("7510.27");
        assertThat(statusOfLease(leaseId)).as("nothing is settled yet").isEqualTo(LeaseStatus.TERMINATED);
        assertTrialBalanceBalances();
    }

    /**
     * Reversing an approved penalty cancels its collection row — and that row can be
     * the last thing the contract was waiting on.
     *
     * <p>The penalty module reaches the register through {@code ChequeService.cancel}
     * (`PenaltyAssessmentService.reverse`), so the hook on that transition is what
     * closes the lease here. It is the path review I1 named as reachable from a
     * screen: finance reverses a fine on a settled tenancy and the contract should
     * finish, not sit open forever waiting for a row nobody will ever collect.</p>
     */
    @Test
    void reversingAPenaltyCancelsItsCollectionRowAndClosesTheLease() {
        UUID leaseId = galahWithOneChequeStillInTheDrawer();
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        // Approved while the contract is still running — the penalty module refuses
        // to charge a lease that has ended — and dated before T so §9.1's default
        // split keeps its collection row rather than handing it back.
        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.OTHER, new BigDecimal("400"), "Lost key"), null);
        penalties.approve(proposed.id(), PENALTY_ON);
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);
        recognition.runTo(T, false);

        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(statusOfLease(leaseId)).as("the collection row is outstanding")
                .isEqualTo(LeaseStatus.TERMINATED);

        penalties.reverse(proposed.id(), SETTLED_ON, "Charged in error");

        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        // The reversal is a *pair*: the PEN is reversed and then the collection row
        // is cancelled, so the receivable ends where it started. That is why the
        // ledger guard on cancel lets this through and refuses a kept cheque — the
        // difference is whether the reversal leaves money owed, not which verb ran.
        assertNothingLeftOnTheLease(leaseId);
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
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // (c) a bounce the settlement absorbed, and a bounce that happens after it
    // ------------------------------------------------------------------

    /**
     * A cheque that bounced <em>before</em> the settlement was drawn has already
     * been paid for by it, and the contract closes without collecting it again
     * (review C-1, lifecycle row 14).
     *
     * <p>The sequence is the real one: the kept January cheque is banked and comes
     * back, so its {@code CBR} puts 12,750 onto the rent receivable (−5,239.73 +
     * 12,750 = 7,510.27). The settlement then nets exactly that against the 3,000
     * deposit and raises a CASH row for the 4,510.27 remainder; receiving it takes
     * the receivable to zero. <b>The bounced debt has now been paid in full</b> —
     * once through the deposit and once in cash — and the register's BOUNCED row is
     * history rather than an open claim.</p>
     *
     * <p>So the register's own statuses cannot be what decides closure: the old
     * rule counted BOUNCED as outstanding, left the lease TERMINATED, and offered
     * {@code replace} as the only exit — whose {@code PDR} credits the receivable a
     * second time and leaves the landlord 12,750 up on a CLOSED contract. Both
     * replacement doors are refused instead, and closure asks the ledger.</p>
     *
     * <p>It is also the proof that the penalty hooks survive a terminated lease:
     * {@code bounce} calls {@code PenaltyRuleEngine.onBounce} inside its own
     * transaction, and a rule that refused to propose on an ended contract would
     * roll the bounce back with it.</p>
     */
    @Test
    void aBounceTheSettlementAbsorbedClosesTheLeaseAndCannotBeCollectedTwice() {
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
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the STL and the collection row between them settled it").isEqualByComparingTo("0.00");

        // Neither replacement door will collect it a second time.
        assertThatThrownBy(() -> cheques.replace(kept, new ReplaceChequeRequest(
                List.of(row("100055", BANKED_ON, BANKED_ON, "12750")), BANKED_ON, "Renter paid by new cheque")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This cheque was settled through the lease settlement");
        assertThatThrownBy(() -> cheques.replaceForOnlinePayment(kept, BANKED_ON))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This cheque was settled through the lease settlement");
        // Six contract rows plus the settlement's own CASH row, and nothing else:
        // neither refusal registered a replacement.
        assertThat(register(leaseId)).as("no replacement row was registered").hasSize(7);

        cheques.receive(collection, ChequeActionRequest.on(SETTLED_ON));

        assertThat(statusOf(kept)).as("it stays on the register as the thing that failed")
                .isEqualTo(ChequeStatus.BOUNCED);
        assertThat(statusOfLease(leaseId))
                .as("the ledger says the tenancy is settled, so it is finished with")
                .isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    /**
     * Issue #297: two bounces on one contract, and the settlement's own one must
     * stay shut.
     *
     * <p>The October cheque (A) cleared, then failed before the statement was
     * drawn, so the {@code STL} netted its 12,750 against the deposit and raised a
     * CASH row for the remainder — A has been paid for. The January cheque (B) is
     * the one §9.1 kept; it bounces a fortnight <em>after</em> finalise, and that
     * genuinely puts 12,750 back on the contract.</p>
     *
     * <p>Deciding from the lease's receivable alone, as the guard used to, that
     * positive balance re-opened {@code replace} on <b>A</b>: its replacement would
     * credit the receivable over money the landlord had already been paid, B would
     * then be locked out of its own replacement, and a contract whose absorbed
     * cheque was the larger of the two could never reach CLOSED. Per cheque, the two
     * are not the same question — A's {@code CBR} is older than the {@code STL} and
     * B's is not — so A stays refused through both doors and B is replaced, cleared
     * and closes the tenancy.</p>
     */
    @Test
    void aBounceAfterFinaliseDoesNotReopenReplaceOnAChequeTheSettlementAbsorbed() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID absorbed = chequeOn(leaseId, RENT_1).getId();
        UUID kept = chequeOn(leaseId, RENT_2).getId();

        // A: cleared in October, returned by the bank before the statement is drawn.
        cheques.bounce(absorbed, ChequeActionRequest.on(RETURNED_BEFORE_SETTLEMENT));
        assertThat(statusOf(absorbed)).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("7510.27");

        SettlementResponseDTO settled = finalizeSettlement(leaseId, null);
        UUID collection = settled.getCollectionChequeId();
        assertThat(collection).as("the row raised for what the deposit could not cover").isNotNull();
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the STL settled everything that was owed when it was posted")
                .isEqualByComparingTo("0.00");

        // B: the kept instrument is banked afterwards and comes back.
        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("B's failure is a debt nobody has been paid for")
                .isEqualByComparingTo("12750.00");

        // A is still the settlement's business, whatever B has done to the balance.
        assertThatThrownBy(() -> cheques.replace(absorbed, new ReplaceChequeRequest(
                List.of(row("100056", BANKED_ON, BANKED_ON, "12750")), BANKED_ON, "Renter paid again")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This cheque was settled through the lease settlement");
        assertThatThrownBy(() -> cheques.replaceForOnlinePayment(absorbed, BANKED_ON))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This cheque was settled through the lease settlement");
        assertThat(register(leaseId))
                .as("six contract rows plus the settlement's CASH row: no replacement was registered")
                .hasSize(7);

        // B is replaced, and the replacement is what finishes the contract off.
        UUID replacement = cheques.replace(kept, new ReplaceChequeRequest(
                        List.of(row("100057", BANKED_ON, BANKED_ON, "12750")), BANKED_ON, "Renter paid by new cheque"))
                .get(0).id();
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.REPLACED);
        clearOnItsOwnDate(chequeById(replacement));
        cheques.receive(collection, ChequeActionRequest.on(SETTLED_ON));

        assertThat(statusOf(absorbed)).as("A stays on the register as the thing that failed")
                .isEqualTo(ChequeStatus.BOUNCED);
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    /**
     * A cheque that bounces <em>after</em> the settlement is finalised is a real
     * debt, and {@code replace} is exactly the right answer to it (lifecycle 13).
     *
     * <p>The mirror of the test above, and the reason the refusal there is ledger-
     * based rather than "this row is BOUNCED on a settled lease": here the
     * {@code CBR} leaves 12,750 genuinely owed, the renter hands over paper for
     * paper, and the replacement's clearance is what closes the contract.</p>
     */
    @Test
    void aKeptChequeThatBouncesAfterFinaliseIsReplacedAndClosesTheLease() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");

        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("the settlement was drawn counting on this cheque, and it failed")
                .isEqualByComparingTo("12750.00");

        List<ChequeDTO> replacements = cheques.replace(kept, new ReplaceChequeRequest(
                List.of(row("100055", BANKED_ON, BANKED_ON, "12750")), BANKED_ON, "Renter paid by new cheque"));
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.REPLACED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("paper for paper: the new PDR puts it back on the register")
                .isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId))
                .as("and the register is holding it").isEqualByComparingTo("12750.00");
        assertThat(statusOfLease(leaseId)).as("the replacement is itself outstanding")
                .isEqualTo(LeaseStatus.TERMINATED);

        UUID replacement = replacements.get(0).id();
        cheques.deposit(replacement, ChequeActionRequest.on(BANKED_ON));
        cheques.clear(replacement, ChequeActionRequest.on(BANKED_ON));

        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    /**
     * …and a replacement that covers only part of the failed cheque leaves the rest
     * owed, so the contract stays open and visibly owing.
     *
     * <p>This is what "closure asks the ledger" buys. The register is empty — the
     * bounced row is REPLACED, the replacement CLEARED, nothing is outstanding by
     * any status test — and the lease is still TERMINATED, because 7,750 of the
     * 12,750 the settlement counted on never arrived. Under the old rule it would
     * have closed here, with a debt and no door left to collect it through
     * (review I-2).</p>
     *
     * <p>What finance does with that residue — a second collection row, a write-off
     * journal that re-evaluates closure — is a product question on #291. Until it is
     * answered, "TERMINATED and owing 7,750" is the honest state.</p>
     */
    @Test
    void aPartialReplacementLeavesTheContractOpenAndVisiblyOwing() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());
        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));

        List<ChequeDTO> replacements = cheques.replace(kept, new ReplaceChequeRequest(
                List.of(row("100055", BANKED_ON, BANKED_ON, "5000")), BANKED_ON, "Part payment"));
        UUID replacement = replacements.get(0).id();
        cheques.deposit(replacement, ChequeActionRequest.on(BANKED_ON));
        cheques.clear(replacement, ChequeActionRequest.on(BANKED_ON));

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.REPLACED);
        assertThat(statusOf(replacement)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId))
                .as("the register is empty").isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId))
                .as("12,750 counted on, 5,000 paid").isEqualByComparingTo("7750.00");
        assertThat(statusOfLease(leaseId)).as("a contract that is still owed money is not finished with")
                .isEqualTo(LeaseStatus.TERMINATED);
        assertThat(closedEvents(leaseId)).isZero();
        assertTrialBalanceBalances();
    }

    /**
     * There is no "write the bounce off with nothing" branch: a replacement with an
     * empty list is refused before anything moves.
     *
     * <p>Worth pinning because the review assumed the branch existed and was the
     * only non-double-collecting exit from a settled bounce. It does not exist —
     * {@code ChequeRowRules.validateNewRows} refuses an empty list — and the exit is
     * the ledger-based closure above rather than a superseding row.</p>
     */
    @Test
    void aReplacementWithNoRowsIsRefused() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.bounce(kept, ChequeActionRequest.on(BANKED_ON));

        assertThatThrownBy(() -> cheques.replace(kept,
                new ReplaceChequeRequest(List.of(), BANKED_ON, "Written off")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("At least one replacement is required");
        assertThatThrownBy(() -> cheques.replace(kept,
                new ReplaceChequeRequest(null, BANKED_ON, "Written off")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("At least one replacement is required");

        assertThat(statusOf(kept)).as("nothing superseded it").isEqualTo(ChequeStatus.BOUNCED);
        assertThat(register(leaseId)).hasSize(6);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("7510.27");
        assertTrialBalanceBalances();
    }

    /**
     * The close is decided on the lease <em>row</em>, not on whatever instance the
     * cheque happened to be carrying (review M-1).
     *
     * <p>A transition reaches the lease through {@code cheque.getLease()}, which may
     * have been resolved before the transition began — and if that read said ACTIVE
     * while the row has since gone TERMINATED with its settlement finalised, the
     * cheap pre-check would return early and the contract would never close. No
     * later clearance could ask again: this <em>was</em> the last instrument.</p>
     *
     * <p>Staged rather than raced: one transaction loads the lease while it is still
     * running, a second ends and settles it, and the kept cheque is then banked
     * inside the first — whose first-level cache still says ACTIVE. The status query
     * behind the pre-check is a scalar projection, so it is not answered from that
     * cache, and {@code lockLease} refreshes the instance under the lock before
     * anything is decided on it.</p>
     */
    @Test
    void aCloseIsNotSkippedBecauseTheLeaseWasLoadedBeforeItEnded() throws Exception {
        UUID leaseId = galahWithOneChequeStillInTheDrawer();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        UUID tenantId = fixtures.tenantId();
        UUID bank = leaf(AccountRole.BANK).getId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                // This transaction meets the lease while it is still running.
                assertThat(leaseRepo.findById(leaseId).orElseThrow().getStatus())
                        .isEqualTo(LeaseStatus.ACTIVE);

                // Somebody else ends and settles it in the meantime.
                Future<?> ending = pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenantId);
                    LeaseTestFixtures.authenticateAsTenantAdmin();
                    try {
                        recognition.runTo(RECOGNISED_TO, false);
                        termination.terminate(leaseId,
                                new TerminateLeaseRequest(T, null, null, null), null);
                        recognition.runTo(T, false);
                        return finalizeSettlement(leaseId, bank);
                    } finally {
                        TenantContextHolder.clear();
                        LeaseTestFixtures.clearAuth();
                    }
                });
                try {
                    ending.get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("the other transaction could not end the lease", e);
                }

                // ...and the last kept instrument is banked in *this* one, whose
                // cached lease still reads ACTIVE.
                cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
                cheques.clear(kept, ChequeActionRequest.on(BANKED_ON));
            });
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.CLEARED);
        assertThat(statusOfLease(leaseId)).as("the row said TERMINATED, so the close happened")
                .isEqualTo(LeaseStatus.CLOSED);
        assertThat(closedEvents(leaseId)).isEqualTo(1);
        assertNothingLeftOnTheLease(leaseId);
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
     * A contract that has ended does not grow new instalments — neither terminated
     * nor expired. The settlement's own door is the single exception, and it is not
     * this one.
     *
     * <p>EXPIRED was left open when this task first landed, on the grounds that
     * approving a penalty raised its collection row through this very method.
     * Review I2 withdrew that: the penalty path now uses the same internal door the
     * settlement's balance-due row uses, and the public grid is a live lease's
     * privilege on both endings. {@code PenaltyAssessmentServiceIT} holds both
     * halves of that pair.</p>
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

    /** …and the same is true of a tenancy that simply ran out (review I2). */
    @Test
    void anExpiredLeaseTakesNoNewGridRowsEither() {
        UUID leaseId = galahWithOneChequeStillInTheDrawer();
        leaseService.markExpired(leaseId, END.plusDays(1));
        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.EXPIRED);

        assertThatThrownBy(() -> cheques.addRowToPostedLease(leaseId, row(null, BANKED_ON, BANKED_ON, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This lease is EXPIRED");
        assertThatThrownBy(() -> cheques.cashReceipt(leaseId,
                new ChequeRowInput(null, null, BANKED_ON, null, BANKED_ON, null, null, null,
                        new BigDecimal("500"), "Cash at the counter", ChequeMode.CASH)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This lease is EXPIRED");

        // The register is untouched and its kept instrument is still collectable:
        // an expired lease is still collecting, it is just not growing.
        assertThat(register(leaseId)).hasSize(6);
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        cheques.deposit(kept, ChequeActionRequest.on(BANKED_ON));
        cheques.clear(kept, ChequeActionRequest.on(BANKED_ON));
        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.CLEARED);
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
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }

    /**
     * A checkout the gateway has not captured keeps the contract open, and the
     * capture closes it.
     *
     * <p>Two rules say so and they agree: an {@code ONLINE_PENDING} row still
     * carries its own {@code PDR}, so PDC receivable is non-zero, and closure names
     * the state outright as well. The second is deliberate belt-and-braces —
     * {@code registerOnlinePending} posts nothing, so the moment some future path
     * moved the balance first, a lease could close underneath a renter who is
     * halfway through paying, and the capture would then meet a CLOSED lease with
     * the money already taken.</p>
     */
    @Test
    void aCheckoutInFlightKeepsTheContractOpenUntilItCaptures() {
        UUID leaseId = terminatedWithAKeptCheque();
        UUID kept = chequeOn(leaseId, RENT_2).getId();
        cheques.registerOnlinePending(kept);
        finalizeSettlement(leaseId, leaf(AccountRole.BANK).getId());

        assertThat(statusOf(kept)).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(statusOfLease(leaseId)).as("a checkout in flight is not nothing")
                .isEqualTo(LeaseStatus.TERMINATED);

        cheques.clearOnline(kept, BANKED_ON, null);

        assertThat(statusOfLease(leaseId)).isEqualTo(LeaseStatus.CLOSED);
        assertNothingLeftOnTheLease(leaseId);
        assertTrialBalanceBalances();
    }
}
