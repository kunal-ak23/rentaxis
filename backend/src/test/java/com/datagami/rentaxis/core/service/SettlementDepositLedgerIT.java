package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.SettlementPreviewDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
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
import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;

/**
 * What the settlement preview says the landlord is holding and what the renter
 * still owes — read off the ledger and the register, not off the contract.
 *
 * <p>Every case here used to come out wrong. The preview read
 * {@code leases.deposit_amount}, which is what the lease <em>charged</em>: a
 * deposit partly refunded mid-term previewed at its full nominal value, and a
 * renewed lease whose deposit had already been carried forward previewed the
 * whole of it a second time — the landlord refunding money that was, by then,
 * sitting against the renter's new contract. Arrears came from
 * {@code payment_schedules}, which no longer exists.</p>
 *
 * <p>An IT rather than a unit test because the subject is arithmetic over
 * {@code journal_lines} and the register: a mock deposit balance would assert
 * only that the code calls the collaborator it obviously calls.</p>
 */
@SpringBootTest
@Testcontainers
class SettlementDepositLedgerIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired SettlementService settlement;
    @Autowired LeaseTerminationService termination;
    @Autowired PenaltyAssessmentService penalties;
    @Autowired ChequeService chequeService;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired PostingService postingService;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    /**
     * A term that started well before today, so some of the grid has matured and
     * some has not — which is the only way "unpaid rent is the DUE rows" can fail
     * visibly. Anchored on today rather than on fixed dates so the test does not
     * quietly stop testing maturity the year it is read.
     */
    private static final LocalDate START = LocalDate.now().minusDays(40);
    private static final LocalDate CONTRACT_DATE = START.minusDays(14);
    private static final LocalDate END = START.plusYears(1).minusDays(1);

    private static final LocalDate RENEWAL_CONTRACT_DATE = END.minusDays(14);
    private static final LocalDate RENEWAL_START = END.plusDays(1);
    private static final LocalDate RENEWAL_END = RENEWAL_START.plusYears(1).minusDays(1);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // the deposit
    // ------------------------------------------------------------------

    /**
     * The contract charged 3,000; 1,000 has since been refunded. The preview owes
     * the renter what is left, not what the contract said.
     */
    @Test
    void previewShowsTheDepositLeftAfterAPartialRefund() {
        UUID leaseId = postedWithDeposit();
        assertThat(preview(leaseId).getDepositAmount()).isEqualByComparingTo("3000");

        refundDeposit(leaseId, "1000");

        assertThat(preview(leaseId).getDepositAmount()).isEqualByComparingTo("2000");
        // And the contract column is untouched, which is the whole point: the two
        // numbers legitimately differ and the preview must be reading the ledger.
        assertThat(reread(leaseId).getDepositAmount()).isEqualByComparingTo("3000");
    }

    /**
     * The renter renewed and their deposit went with them. Settling the contract
     * they left owes them nothing — the money is held against the new one.
     *
     * <p>This is the case that used to refund a deposit the landlord had already
     * moved, i.e. pay it out twice.</p>
     */
    @Test
    void previewOnACarriedForwardPredecessorShowsNothingHeld() {
        UUID predecessor = postedWithDeposit();

        LeaseDTO successor = renewal.renew(predecessor, new RenewLeaseRequest(
                RENEWAL_CONTRACT_DATE, RENEWAL_START, RENEWAL_END, null, true));
        fixtures.generateGrid(successor.getId(), 4, RENEWAL_START);
        posting.post(successor.getId());

        assertThat(reread(predecessor).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(preview(predecessor).getDepositAmount()).isEqualByComparingTo("0");
        assertThat(preview(successor.getId()).getDepositAmount()).isEqualByComparingTo("3000");
    }

    // ------------------------------------------------------------------
    // unpaid rent
    // ------------------------------------------------------------------

    /**
     * Arrears are the register's DUE rows and nothing else: matured instruments
     * that have not cleared. A cheque dated next quarter is not money the renter
     * is withholding, and one that has cleared is money the landlord has.
     */
    @Test
    void unpaidRentIsTheRegistersDueRows() {
        UUID leaseId = postedRentOnly();

        List<Cheque> register = registerOf(leaseId);
        assertThat(register).hasSize(4);
        List<Cheque> due = register.stream()
                .filter(c -> !c.getChequeDate().isAfter(LocalDate.now()))
                .toList();
        assertThat(due).isNotEmpty().hasSizeLessThan(register.size());

        BigDecimal expected = due.stream()
                .map(Cheque::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(preview(leaseId).getUnpaidRentTotal()).isEqualByComparingTo(expected);

        // Clearing the oldest one takes it out of arrears, which a status-blind sum
        // over the lease's cheques would not do.
        Cheque cleared = due.get(0);
        chequeService.deposit(cleared.getId(), ChequeActionRequest.on(LocalDate.now()));
        chequeService.clear(cleared.getId(), ChequeActionRequest.on(LocalDate.now()));
        assertThat(preview(leaseId).getUnpaidRentTotal())
                .isEqualByComparingTo(expected.subtract(cleared.getAmount()));
    }

    // ------------------------------------------------------------------
    // penalties
    // ------------------------------------------------------------------

    /**
     * An approved penalty is deducted once.
     *
     * <p>It is easy to count twice. Approval posts the {@code PEN} <em>and</em>
     * puts a CASH collection row on the register dated that day, which is DUE from
     * the moment it exists — so a preview that summed every due row and then added
     * the outstanding penalties would charge the renter's deposit for the same
     * 500 twice over.</p>
     */
    @Test
    void anApprovedPenaltyIsDeductedOnceAndDisappearsWhenItIsCollected() {
        UUID leaseId = postedRentOnly();
        BigDecimal arrears = preview(leaseId).getUnpaidRentTotal();

        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"),
                "Late again"), null);
        PenaltyAssessmentDTO approved = penalties.approve(proposed.id(), LocalDate.now());

        SettlementPreviewDTO after = preview(leaseId);
        assertThat(after.getPenaltyTotal()).isEqualByComparingTo("500");
        assertThat(after.getUnpaidRentTotal()).isEqualByComparingTo(arrears);

        // Collected: the fine is no longer owed, and the row that carried it stays
        // out of arrears.
        // receive, not deposit-then-clear: the collection row is CASH, and cash
        // over the counter never goes to the bank as paper.
        chequeService.receive(approved.collectionChequeId(), ChequeActionRequest.on(LocalDate.now()));

        SettlementPreviewDTO collected = preview(leaseId);
        assertThat(collected.getPenaltyTotal()).isEqualByComparingTo("0");
        assertThat(collected.getUnpaidRentTotal()).isEqualByComparingTo(arrears);
    }

    /** A proposal is finance still deciding; a settlement does not decide it for them. */
    @Test
    void aProposedPenaltyIsNotDeductedFromTheDeposit() {
        UUID leaseId = postedWithDeposit();
        penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"), "Maybe"), null);

        assertThat(preview(leaseId).getPenaltyTotal()).isEqualByComparingTo("0");
    }

    /**
     * Arrears are the DUE rows and only the DUE rows, including the one the date
     * does not decide.
     *
     * <p>A matured instalment is owed; a bounced one is owed <em>whatever its
     * date</em>, because it already failed; a cheque dated next quarter is not
     * money the renter is withholding. This is the case that would catch
     * {@code findDueForLease} drifting from the register's own {@code findDue} —
     * {@code ChequeRepositoryIT} pins the two queries to each other, and this pins
     * the settlement to the answer.</p>
     */
    @Test
    void arrearsAreTheMaturedRowsPlusTheBouncedOneWhateverItsDate() {
        UUID leaseId = postedRentOnly();
        List<Cheque> register = registerOf(leaseId);
        assertThat(register).hasSize(4);

        Cheque matured = register.get(0);
        assertThat(matured.getChequeDate()).isBeforeOrEqualTo(LocalDate.now());
        Cheque future = register.get(1);
        assertThat(future.getChequeDate()).isAfter(LocalDate.now());

        // The second instalment is banked early and comes back. Its date is still
        // in the future; the debt is live from the moment it failed.
        chequeService.deposit(future.getId(), ChequeActionRequest.on(LocalDate.now()));
        chequeService.bounce(future.getId(),
                new ChequeActionRequest(LocalDate.now(), null, ChequeFailureReason.BOUNCE, null));

        BigDecimal expected = matured.getAmount().add(future.getAmount());
        assertThat(expected).as("51,000 over four instalments").isEqualByComparingTo("25500");
        assertThat(preview(leaseId).getUnpaidRentTotal()).isEqualByComparingTo(expected);
    }

    // ------------------------------------------------------------------
    // a replaced penalty receipt
    // ------------------------------------------------------------------

    /**
     * Replacing the instrument a fine is being collected on does not turn one fine
     * into two debts.
     *
     * <p>Approval raises the {@code PEN} and puts a collection row on the register
     * for it. Two things key off that row: the assessment is outstanding while it
     * has not CLEARED, and the preview leaves rows carrying a
     * {@code penaltyAssessmentId} out of arrears so the fine is not charged to the
     * deposit twice. Before the fix, a replacement carried no link — so it landed
     * in arrears as ordinary rent while the assessment went on pointing at a
     * BOUNCED row that would never clear and went on counting in penalties. One
     * 500 fine, deducted twice, with no screen showing why.</p>
     *
     * <p><b>The collection row is forced to PDC here.</b> {@code approve} writes a
     * CASH row today, and cash cannot be deposited or bounced — so the shape this
     * guards against is not reachable end to end yet, and a test that went through
     * {@code approve} unaltered would fail at the bounce rather than prove
     * anything. The fine collected by cheque is the obvious next thing the client
     * asks for, and the register is one transition away from allowing it; the guard
     * belongs in before then, not after.</p>
     */
    @Test
    void aReplacedPenaltyReceiptIsStillOneFine() {
        UUID leaseId = postedRentOnly();
        BigDecimal arrears = preview(leaseId).getUnpaidRentTotal();

        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"), "Late"), null);
        PenaltyAssessmentDTO approved = penalties.approve(proposed.id(), LocalDate.now());
        UUID collectionId = approved.collectionChequeId();
        asIfCollectedByCheque(collectionId);

        // The renter's cheque for the fine bounces, and they hand over another.
        chequeService.deposit(collectionId, ChequeActionRequest.on(LocalDate.now()));
        chequeService.bounce(collectionId,
                new ChequeActionRequest(LocalDate.now(), null, ChequeFailureReason.BOUNCE, null));
        List<ChequeDTO> replacements = chequeService.replace(collectionId, new ReplaceChequeRequest(
                List.of(new ChequeRowInput(null, null, LocalDate.now(), "900100", LocalDate.now(),
                        "Emirates NBD", null, null, new BigDecimal("500"), "Penalty replacement",
                        ChequeMode.PDC)),
                LocalDate.now(), "Renter re-issued"));
        UUID replacementId = replacements.get(0).id();

        // The link followed the paper.
        assertThat(chequeById(replacementId).getPenaltyAssessmentId())
                .as("the replacement carries the fine it is collecting")
                .isEqualTo(proposed.id());

        SettlementPreviewDTO after = preview(leaseId);
        assertThat(after.getPenaltyTotal()).as("still owed, once").isEqualByComparingTo("500");
        assertThat(after.getUnpaidRentTotal())
                .as("and not a second time as rent")
                .isEqualByComparingTo(arrears);

        // Paid: the fine drops out, and so does the row that carried it.
        chequeService.deposit(replacementId, ChequeActionRequest.on(LocalDate.now()));
        chequeService.clear(replacementId, ChequeActionRequest.on(LocalDate.now()));

        SettlementPreviewDTO collected = preview(leaseId);
        assertThat(collected.getPenaltyTotal()).isEqualByComparingTo("0");
        assertThat(collected.getUnpaidRentTotal()).isEqualByComparingTo(arrears);
    }

    /**
     * Reversing an approval still finds its collection row after the row has been
     * replaced — the assessment points at the replacement, which is REGISTERED,
     * which is exactly the state {@code reverse} cancels.
     */
    @Test
    void anApprovalCanStillBeReversedAfterItsCollectionRowWasReplaced() {
        UUID leaseId = postedRentOnly();
        BigDecimal arrears = preview(leaseId).getUnpaidRentTotal();

        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"), "Late"), null);
        PenaltyAssessmentDTO approved = penalties.approve(proposed.id(), LocalDate.now());
        UUID collectionId = approved.collectionChequeId();
        asIfCollectedByCheque(collectionId);

        chequeService.deposit(collectionId, ChequeActionRequest.on(LocalDate.now()));
        chequeService.bounce(collectionId,
                new ChequeActionRequest(LocalDate.now(), null, ChequeFailureReason.BOUNCE, null));
        chequeService.replace(collectionId, new ReplaceChequeRequest(
                List.of(new ChequeRowInput(null, null, LocalDate.now(), "900101", LocalDate.now(),
                        "Emirates NBD", null, null, new BigDecimal("500"), "Penalty replacement",
                        ChequeMode.PDC)),
                LocalDate.now(), null));

        PenaltyAssessmentDTO reversed = penalties.reverse(proposed.id(), LocalDate.now(), "Raised in error");

        assertThat(reversed.status()).isEqualTo(PenaltyAssessmentStatus.REVERSED);
        assertThat(chequeById(reversed.collectionChequeId()).getStatus()).isEqualTo(ChequeStatus.CANCELLED);

        SettlementPreviewDTO after = preview(leaseId);
        assertThat(after.getPenaltyTotal()).isEqualByComparingTo("0");
        assertThat(after.getUnpaidRentTotal()).isEqualByComparingTo(arrears);
    }

    /**
     * Without a tenant in context every figure a settlement is built from is
     * unreliable: the deposit and the arrears are JPQL reads that depend on the
     * Hibernate tenant filter, which {@code TenantAspect} only enables when one is
     * set. Refused in the service's own terms rather than left to surface from
     * inside {@code LeaseDepositLedger} as an error about a deposit balance.
     */
    @Test
    void aSettlementWithoutATenantInContextIsRefused() {
        UUID leaseId = postedWithDeposit();
        TenantContextHolder.clear();

        assertThatThrownBy(() -> settlement.getSettlementPreview(leaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    /**
     * A settlement cannot be finalised on a contract that is still running.
     *
     * <p>Finalising used to terminate the lease as a side effect. It no longer does
     * — termination is its own act with its own date, its own cheque decisions and
     * its own journals (spec §9.1), and the statement this finalises is drawn from
     * the receivable that termination leaves behind (§9.2). Without this guard the
     * two simply came apart: a FINALIZED settlement with the deposit deemed
     * released, on an ACTIVE lease whose unit is still occupied, whose uncleared
     * cheques are still on the register and whose rent the nightly job is still
     * recognising. The web screen even tells the operator the lease was
     * terminated.</p>
     */
    @Test
    void aSettlementCannotBeFinalisedWhileTheLeaseIsStillRunning() {
        UUID leaseId = postedWithDeposit();
        settlement.saveDraft(leaseId, new SaveSettlementDTO(), null);

        assertThatThrownBy(() -> settlement.finalizeSettlement(leaseId, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Terminate the lease before settling it; this one is ACTIVE.");

        assertThat(settlement.buildSettlementResponse(leaseId).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());
    }

    /** ...and once it has been terminated, the same call goes through. */
    @Test
    void aSettlementOnATerminatedLeaseIsFinalised() {
        UUID leaseId = postedWithDeposit();
        settlement.saveDraft(leaseId, new SaveSettlementDTO(), null);
        termination.terminate(leaseId, new TerminateLeaseRequest(
                LocalDate.now(), null, null, "Renter moved out"), null);

        LeaseSettlement finalized = settlement.finalizeSettlement(leaseId, null);

        assertThat(finalized.getStatus()).isEqualTo(SettlementStatus.FINALIZED);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 51,000 of rent in four cheques plus a 3,000 deposit, on the books. */
    private UUID postedWithDeposit() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                        List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "3000")), 4, null)
                .lease().getId();
    }

    /** 51,000 of rent in four cheques, no deposit — so arrears are rent and only rent. */
    private UUID postedRentOnly() {
        setUnitVacant();
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                        List.of(line("RENT", "51000")), 4, null)
                .lease().getId();
    }

    private SettlementPreviewDTO preview(UUID leaseId) {
        return settlement.getSettlementPreview(leaseId);
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private List<Cheque> registerOf(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    /**
     * Hand part of the deposit back: {@code Dr SECURITY_DEPOSIT} on the lease's
     * dimension, {@code Cr BANK}. The same shape a refund voucher will have in
     * plan 4 — what matters here is that the deposit account's balance on this
     * lease drops.
     */
    private void refundDeposit(UUID leaseId, String amount) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            Account deposit = resolver.resolve(AccountRole.SECURITY_DEPOSIT, fixtures.property().getId());
            Account bank = resolver.resolve(AccountRole.BANK, fixtures.property().getId());
            BigDecimal value = new BigDecimal(amount);
            PostingRequest.Dimensions dims = LeaseChequeRegistrar.dimensions(lease, null);
            postingService.post(PostingRequest.ofPairs(
                    JournalDocType.JV,
                    LocalDate.now(),
                    "Partial deposit refund",
                    dims,
                    JournalSourceType.LEASE,
                    lease.getId(),
                    null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(deposit.getId(), value).withDims(dims),
                            PostingRequest.cr(bank.getId(), value).withDims(dims)))));
        });
    }

    private Cheque chequeById(UUID id) {
        return tx.execute(s -> chequeRepo.findById(id).orElseThrow());
    }

    /**
     * Turns an approved penalty's CASH collection row into a PDC one, so the fine
     * can be banked, bounce and be replaced. See
     * {@link #aReplacedPenaltyReceiptIsStillOneFine} for why this is done by hand.
     */
    private void asIfCollectedByCheque(UUID collectionChequeId) {
        tx.executeWithoutResult(s -> {
            Cheque row = chequeRepo.findById(collectionChequeId).orElseThrow();
            row.setMode(ChequeMode.PDC);
            row.setChequeNumber("900000");
            chequeRepo.save(row);
        });
    }

    /** Frees the unit so a second lease can be drafted on it. */
    private void setUnitVacant() {
        tx.executeWithoutResult(s -> {
            Unit unit = unitRepo.findById(fixtures.unit().getId()).orElseThrow();
            unit.setStatus(UnitStatus.VACANT);
            unit.setCurrentTenantName(null);
            unitRepo.save(unit);
        });
    }
}
