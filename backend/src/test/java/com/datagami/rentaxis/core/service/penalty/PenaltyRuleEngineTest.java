package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.core.service.FineConfig;
import com.datagami.rentaxis.core.service.FineConfigResolver;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules that decide whether finance is shown a proposal at all.
 *
 * <p>Mocked rather than wired: every assertion here is about a <em>decision</em>
 * — fired or did not fire, and for how much — and none of them is about what
 * lands in the ledger. The end-to-end behaviour through
 * {@code ChequeService.bounce} is {@code PenaltyAssessmentServiceIT}'s.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PenaltyRuleEngineTest {

    @Mock FineConfigResolver fineConfigResolver;
    @Mock RentCollectionSettingsRepository rentCollectionSettings;
    @Mock ChequeRepository chequeRepository;
    @Mock PenaltyAssessmentRepository assessments;
    @Mock PenaltyAssessmentService assessmentService;

    private PenaltyRuleEngine engine;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID leaseId = UUID.randomUUID();

    private Lease lease;
    private Property property;

    @BeforeEach
    void setUp() {
        engine = new PenaltyRuleEngine(fineConfigResolver, rentCollectionSettings,
                chequeRepository, assessments, assessmentService);
        TenantContextHolder.setTenantId(tenantId);

        property = new Property();
        property.setId(propertyId);
        lease = new Lease();
        lease.setId(leaseId);
        lease.setTenantId(tenantId);

        when(rentCollectionSettings.findByPropertyId(propertyId)).thenReturn(Optional.empty());
        when(assessments.existsByCheque_IdAndReasonAndStatusIn(any(), any(), anyCollection())).thenReturn(false);
        when(assessmentService.proposeBySystem(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PenaltyAssessment());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private FineConfig cfg(int threshold, boolean chequeReturn, boolean latePayment) {
        return new FineConfig(
                new BigDecimal("500"), new BigDecimal("750"), new BigDecimal("1000"),
                7, new BigDecimal("25"),
                threshold, chequeReturn, latePayment, FineConfig.Source.ORG);
    }

    private void fineConfig(FineConfig cfg) {
        when(fineConfigResolver.resolve(propertyId, tenantId)).thenReturn(cfg);
    }

    private Cheque cheque(String number, String amount, LocalDate chequeDate, ChequeFailureReason failure) {
        Cheque c = new Cheque();
        c.setId(UUID.randomUUID());
        c.setTenantId(tenantId);
        c.setLease(lease);
        c.setProperty(property);
        c.setSeqNo(1);
        c.setChequeNumber(number);
        c.setChequeDate(chequeDate);
        c.setAmount(new BigDecimal(amount));
        c.setFailureReason(failure);
        return c;
    }

    private void bouncesOnThisLease(long count) {
        when(chequeRepository.countPenalisableBounces(leaseId)).thenReturn(count);
    }

    /**
     * The property's late-payment rules. Its {@code gracePeriodDays} is set to a
     * deliberately different number from the lease's in every test that has both:
     * it is a property-wide default the engine must not be reading.
     */
    private RentCollectionSettings settings(PenaltyType type, String amount, Integer grace) {
        RentCollectionSettings s = new RentCollectionSettings();
        s.setPenaltyType(type);
        s.setPenaltyAmount(amount == null ? null : new BigDecimal(amount));
        s.setGracePeriodDays(grace);
        when(rentCollectionSettings.findByPropertyId(propertyId)).thenReturn(Optional.of(s));
        return s;
    }

    /** The grace that actually decides lateness — the same field the register is overdue by. */
    private void leaseGrace(int days) {
        lease.setGracePeriodDays(days);
    }

    private BigDecimal proposedAmount() {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(assessmentService).proposeBySystem(eq(lease), any(), any(), amount.capture(), any(), any(), any(), any());
        return amount.getValue();
    }

    // ------------------------------------------------------------------
    // onBounce
    // ------------------------------------------------------------------

    @Test
    void belowTheThresholdNothingIsProposed() {
        fineConfig(cfg(2, true, false));
        bouncesOnThisLease(1);

        engine.onBounce(cheque("100040", "12750", LocalDate.of(2026, 10, 2), ChequeFailureReason.BOUNCE));

        verifyNoInteractions(assessmentService);
    }

    /** Inclusive: a threshold of 2 fires on the second returned cheque, not the third. */
    @Test
    void atTheThresholdOneProposalIsRaisedForTheConfiguredBounceFine() {
        fineConfig(cfg(2, true, false));
        bouncesOnThisLease(2);
        Cheque c = cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE);
        c.setBouncedAt(LocalDate.of(2026, 11, 9));

        engine.onBounce(c);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        // F14-23: the incident is the bounce date; F14-31: the description as a code.
        verify(assessmentService).proposeBySystem(eq(lease), eq(c), eq(PenaltyReason.CHEQUE_RETURN),
                eq(new BigDecimal("500")), description.capture(), eq(LocalDate.of(2026, 11, 9)),
                eq("chequeReturned"), eq(java.util.Map.of("cheque", "100041", "failureReason", "BOUNCE", "bounces", "2")));
        assertThat(description.getValue())
                .contains("100041")
                .contains("BOUNCE")
                .contains("bounce #2");
    }

    /** The fine follows the reason the bank gave, not a single flat number. */
    @Test
    void theFailureReasonChoosesWhichFineIsProposed() {
        fineConfig(cfg(1, true, false));
        bouncesOnThisLease(1);

        engine.onBounce(cheque("100042", "12750", LocalDate.of(2026, 12, 2),
                ChequeFailureReason.ACCOUNT_CLOSED));

        assertThat(proposedAmount()).isEqualByComparingTo("1000");
    }

    /** F14-22: a stopped payment is the renter's doing — the generic bounce fee. */
    @Test
    void aStoppedPaymentCarriesTheGenericBounceFee() {
        fineConfig(cfg(1, true, false));
        bouncesOnThisLease(1);

        engine.onBounce(cheque("100043", "12750", LocalDate.of(2026, 12, 2), ChequeFailureReason.STOPPED_PAYMENT));

        assertThat(proposedAmount()).isEqualByComparingTo("500");
    }

    /**
     * F14-22 ruling: a technical return (stale, post-dated, words/figures mismatch) is
     * the bank's error — no fee is proposed, however many bounces the lease has.
     */
    @Test
    void aTechnicalReturnProposesNoFee() {
        fineConfig(cfg(1, true, false));

        engine.onBounce(cheque("100044", "12750", LocalDate.of(2026, 12, 2), ChequeFailureReason.TECHNICAL_RETURN));

        verify(assessmentService, org.mockito.Mockito.never()).proposeBySystem(any(), any(), any(), any(), any(), any(), any(), any());
        verify(chequeRepository, org.mockito.Mockito.never()).countPenalisableBounces(any());
        assertThat(new FineConfig(new BigDecimal("500"), new BigDecimal("750"), new BigDecimal("1000"), 7,
                new BigDecimal("25"), 1, true, false, FineConfig.Source.ORG)
                .amountFor(ChequeFailureReason.TECHNICAL_RETURN)).isEqualByComparingTo("0");
    }

    /**
     * The same returned cheque reaching the hook twice — a retried request, or a
     * bounce recorded, reversed and recorded again — must not put two fines for one
     * instrument in front of finance.
     */
    @Test
    void aChequeThatAlreadyHasALiveProposalIsNotProposedAgain() {
        fineConfig(cfg(2, true, false));
        bouncesOnThisLease(3);
        when(assessments.existsByCheque_IdAndReasonAndStatusIn(
                any(), eq(PenaltyReason.CHEQUE_RETURN), anyCollection())).thenReturn(true);

        engine.onBounce(cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE));

        verifyNoInteractions(assessmentService);
    }

    @Test
    void autoProposalTurnedOffProposesNothingHoweverManyBounced() {
        fineConfig(cfg(2, false, false));
        bouncesOnThisLease(9);

        engine.onBounce(cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE));

        verifyNoInteractions(assessmentService);
    }

    /** A landlord who configured the fine as zero has said they do not charge one. */
    @Test
    void aZeroFineProposesNothing() {
        fineConfig(new FineConfig(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                7, new BigDecimal("25"), 1, true, false, FineConfig.Source.ORG));
        bouncesOnThisLease(4);

        engine.onBounce(cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE));

        verifyNoInteractions(assessmentService);
    }

    /**
     * The threshold is whatever the resolved config says — including a property
     * override, which {@code FineConfigResolver} has already coalesced over the
     * organisation's by the time it gets here. The engine does not re-read
     * rent_collection_settings for it: two places deciding one threshold is two
     * places that can disagree.
     */
    @Test
    void theThresholdIsTheResolvedOneAndIsNotLookedUpTwice() {
        fineConfig(cfg(4, true, false));
        // A settings row that still carries its own number: if the engine read it,
        // three bounces would be enough and the first call below would propose.
        RentCollectionSettings ignored = settings(PenaltyType.NONE, null, null);
        ignored.setBouncesBeforePenalty(3);

        bouncesOnThisLease(3);
        engine.onBounce(cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE));
        verifyNoInteractions(assessmentService);

        bouncesOnThisLease(4);
        Cheque fourth = cheque("100042", "12750", LocalDate.of(2026, 12, 2), ChequeFailureReason.BOUNCE);
        engine.onBounce(fourth);
        verify(assessmentService).proposeBySystem(eq(lease), eq(fourth), eq(PenaltyReason.CHEQUE_RETURN),
                eq(new BigDecimal("500")), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------
    // onLateClear
    // ------------------------------------------------------------------

    @Test
    void latePaymentProposalIsOffUnlessTheLandlordTurnedItOn() {
        fineConfig(cfg(2, true, false));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(5);

        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 30));

        verifyNoInteractions(assessmentService);
    }

    /** Auto-propose on is not a way past a landlord who charges nothing for lateness. */
    @Test
    void aPropertyWithNoLatePenaltyTypeProposesNothing() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.NONE, "50", 30);
        leaseGrace(5);

        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 30));

        verifyNoInteractions(assessmentService);
    }

    @Test
    void clearingOnTheLastDayOfTheLeasesGraceIsNotLate() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(5);

        // Due 2 Oct + the lease's 5 days = 7 Oct; the money landed on the 7th.
        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 7));

        verifyNoInteractions(assessmentService);
    }

    @Test
    void clearingPastTheLeasesGraceProposesTheWholeLateFeeAtOnce() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(5);
        Cheque c = cheque("100040", "12750", LocalDate.of(2026, 10, 2), null);

        // 7 Oct effective due, cleared 17 Oct: ten days late at 50/day.
        engine.onLateClear(c, LocalDate.of(2026, 10, 17));

        verify(assessmentService).proposeBySystem(eq(lease), eq(c), eq(PenaltyReason.LATE_PAYMENT),
                eq(new BigDecimal("500")), any(), eq(LocalDate.of(2026, 10, 17)), eq("clearedLate"), any());
    }

    /**
     * The lateness window is the lease's, not the property's.
     *
     * <p>{@code ChequeMapper} hands {@code lease.getGracePeriodDays()} to
     * {@code ChequeDueRules}, so that is the window the register is already
     * flagging the row overdue by. Proposing off {@code RentCollectionSettings}'
     * separate grace column would fine a renter for days the screen chasing them
     * never called late — here, the property's 30 days would have swallowed the
     * whole delay and proposed nothing at all.
     */
    @Test
    void thePropertysOwnGraceColumnIsIgnored() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(2);
        Cheque c = cheque("100040", "12750", LocalDate.of(2026, 10, 2), null);

        // 4 Oct effective due by the lease, 3 days late on the 7th. By the property's
        // 30 days it would not be late at all.
        engine.onLateClear(c, LocalDate.of(2026, 10, 7));

        assertThat(proposedAmount()).isEqualByComparingTo("150");
    }

    /**
     * A fine is not itself fine-able.
     *
     * <p>Approving a penalty puts a CASH collection row on the register dated the
     * approval day, and most leases carry no grace at all — so finance receiving it
     * a week later auto-proposed a LATE_PAYMENT penalty <em>on the penalty</em>.
     * Only a proposal, so nobody was charged twice, but it is a row on the worklist
     * that exists for no reason anybody can explain to a renter.</p>
     */
    @Test
    void aPenaltyCollectionRowIsNeverProposedALateFeeOfItsOwn() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(0);
        Cheque collection = cheque(null, "500", LocalDate.of(2026, 10, 2), null);
        collection.setPenaltyAssessmentId(UUID.randomUUID());

        // Ten days after the approval day, which on an ordinary rent row would be
        // 500 AED of late fee.
        engine.onLateClear(collection, LocalDate.of(2026, 10, 12));

        verifyNoInteractions(assessmentService);
    }

    /** The lease's default of zero makes the cheque date itself the deadline. */
    @Test
    void aLeaseWithNoGraceIsLateTheDayAfterTheChequeDate() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 30);
        leaseGrace(0);
        Cheque c = cheque("100040", "12750", LocalDate.of(2026, 10, 2), null);

        engine.onLateClear(c, LocalDate.of(2026, 10, 2));
        verifyNoInteractions(assessmentService);

        engine.onLateClear(c, LocalDate.of(2026, 10, 3));
        assertThat(proposedAmount()).isEqualByComparingTo("50");
    }

    // ------------------------------------------------------------------
    // lateAmount: pinned to the arithmetic it was copied from
    // ------------------------------------------------------------------

    /**
     * {@link PenaltyRuleEngine#lateAmount} is a copy of v1's
     * {@code PenaltyCalculationService.calculatePenalty}, taken so that class could
     * be deleted without the formula going with it. The expectations below were
     * computed by that class before it was deleted (changeset 84) and are pinned
     * here as literals — the two used to be run against each other, which stopped
     * being possible once one of them no longer existed.
     *
     * <p>The percentage branch is the one that matters. It rounds the daily figure
     * to two decimals <em>before</em> multiplying by the days: 9,999 at 0.5% is
     * 49.995, which rounds to 50.00 and gives 500.00 over ten days, where an
     * unrounded chain gives 499.95. The gap grows with the number of days, so this
     * is part of the formula rather than an implementation detail — a drifting copy
     * would have the register's running estimate and the proposal finance actually
     * approves quoting different money for the same lateness.</p>
     */
    @ParameterizedTest
    @CsvSource({
            "FIXED_PER_DAY, 50,   12750, 1,  50",
            "FIXED_PER_DAY, 50,   12750, 10, 500",
            "FIXED_PER_DAY, 12.5, 12750, 3,  37.5",
            "PERCENTAGE,    0.5,  12750, 1,  63.75",
            "PERCENTAGE,    0.5,  12750, 10, 637.50",
            "PERCENTAGE,    0.5,  9999,  10, 500.00",
            "PERCENTAGE,    2,    9999,  7,  1399.86",
            "PERCENTAGE,    0.01, 1000,  30, 3.00"
    })
    void lateAmountMatchesTheV1Formula(PenaltyType type, BigDecimal rate,
                                       BigDecimal amount, int daysLate, BigDecimal expected) {
        RentCollectionSettings s = new RentCollectionSettings();
        s.setPenaltyType(type);
        s.setPenaltyAmount(rate);
        s.setGracePeriodDays(0);

        assertThat(PenaltyRuleEngine.lateAmount(amount, s, daysLate)).isEqualByComparingTo(expected);
    }

    @Test
    void lateAmountIsZeroWhenThereIsNothingToCharge() {
        RentCollectionSettings none = new RentCollectionSettings();
        none.setPenaltyType(PenaltyType.NONE);
        none.setPenaltyAmount(new BigDecimal("50"));

        RentCollectionSettings fixed = new RentCollectionSettings();
        fixed.setPenaltyType(PenaltyType.FIXED_PER_DAY);
        fixed.setPenaltyAmount(new BigDecimal("50"));

        assertThat(PenaltyRuleEngine.lateAmount(new BigDecimal("12750"), none, 10)).isEqualByComparingTo("0");
        assertThat(PenaltyRuleEngine.lateAmount(new BigDecimal("12750"), null, 10)).isEqualByComparingTo("0");
        assertThat(PenaltyRuleEngine.lateAmount(new BigDecimal("12750"), fixed, 0)).isEqualByComparingTo("0");
        assertThat(PenaltyRuleEngine.lateAmount(new BigDecimal("12750"), fixed, -3)).isEqualByComparingTo("0");
    }

    /** No rule may fire without a cheque, a lease or a property to attribute it to. */
    @Test
    void anUnattributableRowFiresNothing() {
        engine.onBounce(null);
        engine.onLateClear(null, LocalDate.of(2026, 10, 17));

        Cheque orphan = new Cheque();
        orphan.setId(UUID.randomUUID());
        engine.onBounce(orphan);
        engine.onLateClear(orphan, LocalDate.of(2026, 10, 17));

        verifyNoInteractions(assessmentService);
        verify(fineConfigResolver, never()).resolve(any(), any());
    }
}
