package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.core.service.FineConfig;
import com.datagami.rentaxis.core.service.FineConfigResolver;
import com.datagami.rentaxis.core.service.PenaltyCalculationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
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
        when(assessmentService.proposeBySystem(any(), any(), any(), any(), any()))
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
        when(chequeRepository.countByLease_IdAndBouncedAtIsNotNull(leaseId)).thenReturn(count);
    }

    private RentCollectionSettings settings(PenaltyType type, String amount, Integer grace) {
        RentCollectionSettings s = new RentCollectionSettings();
        s.setPenaltyType(type);
        s.setPenaltyAmount(amount == null ? null : new BigDecimal(amount));
        s.setGracePeriodDays(grace);
        when(rentCollectionSettings.findByPropertyId(propertyId)).thenReturn(Optional.of(s));
        return s;
    }

    private BigDecimal proposedAmount() {
        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(assessmentService).proposeBySystem(eq(lease), any(), any(), amount.capture(), any());
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

        engine.onBounce(c);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(assessmentService).proposeBySystem(eq(lease), eq(c), eq(PenaltyReason.CHEQUE_RETURN),
                eq(new BigDecimal("500")), description.capture());
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

    /** The property's own threshold wins over the organisation's. */
    @Test
    void aPropertyThresholdOverridesTheOrganisationOne() {
        fineConfig(cfg(2, true, false));
        RentCollectionSettings s = settings(PenaltyType.NONE, null, null);
        s.setBouncesBeforePenalty(4);
        bouncesOnThisLease(2);

        engine.onBounce(cheque("100041", "12750", LocalDate.of(2026, 11, 2), ChequeFailureReason.BOUNCE));

        verifyNoInteractions(assessmentService);
    }

    // ------------------------------------------------------------------
    // onLateClear
    // ------------------------------------------------------------------

    @Test
    void latePaymentProposalIsOffUnlessTheLandlordTurnedItOn() {
        fineConfig(cfg(2, true, false));
        settings(PenaltyType.FIXED_PER_DAY, "50", 5);

        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 30));

        verifyNoInteractions(assessmentService);
    }

    /** Auto-propose on is not a way past a landlord who charges nothing for lateness. */
    @Test
    void aPropertyWithNoLatePenaltyTypeProposesNothing() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.NONE, "50", 5);

        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 30));

        verifyNoInteractions(assessmentService);
    }

    @Test
    void clearingInsideTheGracePeriodIsNotLate() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 5);

        // Due 2 Oct + 5 days grace = 7 Oct; the money landed on the 7th.
        engine.onLateClear(cheque("100040", "12750", LocalDate.of(2026, 10, 2), null),
                LocalDate.of(2026, 10, 7));

        verifyNoInteractions(assessmentService);
    }

    @Test
    void clearingPastTheGracePeriodProposesTheWholeLateFeeAtOnce() {
        fineConfig(cfg(2, true, true));
        settings(PenaltyType.FIXED_PER_DAY, "50", 5);
        Cheque c = cheque("100040", "12750", LocalDate.of(2026, 10, 2), null);

        // 7 Oct effective due, cleared 17 Oct: ten days late at 50/day.
        engine.onLateClear(c, LocalDate.of(2026, 10, 17));

        verify(assessmentService).proposeBySystem(eq(lease), eq(c), eq(PenaltyReason.LATE_PAYMENT),
                eq(new BigDecimal("500")), any());
    }

    // ------------------------------------------------------------------
    // lateAmount: pinned against the v1 arithmetic it was copied from
    // ------------------------------------------------------------------

    /**
     * {@link PenaltyRuleEngine#lateAmount} is a copy of
     * {@code PenaltyCalculationService.calculatePenalty}, taken so the v1 class can
     * be deleted in task 12 without the formula going with it. A copy that drifts
     * is worse than no copy: the register's running estimate and the proposal
     * finance actually approves would quote different money for the same lateness.
     * So both are computed here over the same inputs and required to agree.
     *
     * <p>The percentage branch is the one that matters. It rounds the daily figure
     * to two decimals <em>before</em> multiplying by the days, which for 12,750 at
     * 0.5% over 10 days is 637.50 rather than the 637.5 an unrounded chain gives —
     * a difference that grows with the number of days.</p>
     */
    @ParameterizedTest
    @CsvSource({
            "FIXED_PER_DAY, 50,   12750, 1",
            "FIXED_PER_DAY, 50,   12750, 10",
            "FIXED_PER_DAY, 12.5, 12750, 3",
            "PERCENTAGE,    0.5,  12750, 1",
            "PERCENTAGE,    0.5,  12750, 10",
            "PERCENTAGE,    2,    9999,  7",
            "PERCENTAGE,    0.01, 1000,  30"
    })
    void lateAmountAgreesWithTheV1Calculation(PenaltyType type, BigDecimal rate,
                                              BigDecimal amount, int daysLate) {
        RentCollectionSettings s = new RentCollectionSettings();
        s.setPenaltyType(type);
        s.setPenaltyAmount(rate);
        s.setGracePeriodDays(0);

        PaymentSchedule schedule = new PaymentSchedule();
        schedule.setDueDate(LocalDate.of(2026, 10, 2));
        schedule.setAmount(amount);

        BigDecimal v1 = new PenaltyCalculationService()
                .calculatePenalty(schedule, s, schedule.getDueDate().plusDays(daysLate));

        assertThat(PenaltyRuleEngine.lateAmount(amount, s, daysLate)).isEqualByComparingTo(v1);
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
