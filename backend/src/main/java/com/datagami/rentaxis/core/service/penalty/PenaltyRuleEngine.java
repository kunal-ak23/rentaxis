package com.datagami.rentaxis.core.service.penalty;

import com.datagami.rentaxis.core.service.FineConfig;
import com.datagami.rentaxis.core.service.FineConfigResolver;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.PenaltyType;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * When the register's own events are worth putting in front of finance
 * (spec §7.3).
 *
 * <p>Nothing here posts, and that is the whole point. The rules decide whether a
 * <em>proposal</em> appears on the worklist; the charge exists only once somebody
 * approves it. So a rule that fires too eagerly costs a row someone dismisses,
 * not a fine a renter has to argue their way out of.</p>
 *
 * <p><b>Both hooks run inside the transition that caused them.</b> A bounce and
 * its proposal are one business action: if the proposal cannot be written, the
 * bounce has not really been recorded the way the landlord's policy says it
 * should be, and half of it committing would leave a returned cheque that
 * silently never reached finance. The register and the worklist move together or
 * neither moves.</p>
 *
 * <p>{@code Propagation.MANDATORY} is how that stays true. These are not entry
 * points: a caller that reached one outside a transaction would get the tenant
 * filter disabled — {@code TenantAspect} only enables it inside one — and would
 * write a proposal that commits whatever happens to the transition. Refusing
 * outright is better than either.</p>
 */
@Component
public class PenaltyRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(PenaltyRuleEngine.class);

    private final FineConfigResolver fineConfigResolver;
    private final RentCollectionSettingsRepository rentCollectionSettings;
    private final ChequeRepository chequeRepository;
    private final PenaltyAssessmentRepository assessments;
    private final PenaltyAssessmentService assessmentService;

    public PenaltyRuleEngine(FineConfigResolver fineConfigResolver,
                             RentCollectionSettingsRepository rentCollectionSettings,
                             ChequeRepository chequeRepository,
                             PenaltyAssessmentRepository assessments,
                             PenaltyAssessmentService assessmentService) {
        this.fineConfigResolver = fineConfigResolver;
        this.rentCollectionSettings = rentCollectionSettings;
        this.chequeRepository = chequeRepository;
        this.assessments = assessments;
        this.assessmentService = assessmentService;
    }

    // ------------------------------------------------------------------
    // a returned cheque
    // ------------------------------------------------------------------

    /**
     * The bank returned a cheque. Propose a fine only once this lease has bounced
     * enough times to cross the landlord's threshold.
     *
     * <p>The threshold is per lease and inclusive — the default of 2 proposes on
     * the second returned cheque, not the third — because that is how the client's
     * accountant described it: the first one is a mistake, the second is a
     * pattern. The count is taken <em>after</em> the current bounce is recorded,
     * so the cheque that triggered this is included in it.</p>
     *
     * <p>A threshold crossed a third and fourth time proposes again each time:
     * each returned instrument is its own fine. What is guarded is proposing twice
     * over the <em>same</em> cheque.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onBounce(Cheque cheque) {
        Lease lease = cheque == null ? null : cheque.getLease();
        UUID propertyId = propertyIdOf(cheque, lease);
        if (lease == null || propertyId == null) return;

        FineConfig cfg = fineConfigResolver.resolve(propertyId, TenantContextHolder.getTenantId());
        if (!cfg.autoProposeChequeReturn()) return;

        // The resolver already coalesced the property's override over the
        // organisation's, exactly as it does for every fine amount. Reading
        // rent_collection_settings a second time here was the same answer arrived at
        // twice — and two places that have to agree about a threshold eventually
        // will not.
        Integer threshold = cfg.bouncesBeforePenalty();
        if (threshold == null) return;

        long bounces = chequeRepository.countByLease_IdAndBouncedAtIsNotNull(lease.getId());
        if (bounces < threshold) return;

        if (assessments.existsByCheque_IdAndReasonAndStatusIn(
                cheque.getId(), PenaltyReason.CHEQUE_RETURN, PenaltyAssessmentService.OPEN)) {
            return;
        }

        ChequeFailureReason failure = cheque.getFailureReason();
        // amountFor switches on the reason, so a bounce recorded without one falls
        // back to the plain bounce fine rather than throwing inside the transition.
        BigDecimal amount = failure != null ? cfg.amountFor(failure) : cfg.bounceAmount();
        if (amount == null || amount.signum() <= 0) {
            log.info("No cheque-return penalty proposed for cheque {}: the configured fine for {} is {}",
                    cheque.getId(), failure, amount);
            return;
        }

        assessmentService.proposeBySystem(lease, cheque, PenaltyReason.CHEQUE_RETURN, amount,
                "Cheque " + label(cheque) + " returned"
                        + (failure != null ? " (" + failure + ")" : "")
                        + ", bounce #" + bounces + " on this lease");
    }

    // ------------------------------------------------------------------
    // rent that arrived late
    // ------------------------------------------------------------------

    /**
     * A cheque cleared after its grace period.
     *
     * <p>Off by default, and gated twice over: the landlord has to have turned
     * auto-proposal on <em>and</em> the property has to have a late-payment
     * penalty configured at all. A landlord who set {@code penaltyType = NONE} has
     * said they do not charge for lateness, and the auto-propose switch is not a
     * way around that.</p>
     *
     * <p>Once per cheque, not once per day. The v1 behaviour accrued nightly,
     * which is why a renter three months late arrived at a balance nobody had
     * decided on; here the whole late fee is proposed the day the money actually
     * lands, as one number finance can look at.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onLateClear(Cheque cheque, LocalDate clearedOn) {
        Lease lease = cheque == null ? null : cheque.getLease();
        UUID propertyId = propertyIdOf(cheque, lease);
        if (lease == null || propertyId == null || clearedOn == null || cheque.getChequeDate() == null) return;

        FineConfig cfg = fineConfigResolver.resolve(propertyId, TenantContextHolder.getTenantId());
        if (!cfg.autoProposeLatePayment()) return;

        RentCollectionSettings settings = rentCollectionSettings.findByPropertyId(propertyId).orElse(null);
        if (settings == null || settings.getPenaltyType() == null
                || settings.getPenaltyType() == PenaltyType.NONE) {
            return;
        }

        // The LEASE's grace, which is the field the register itself is late by:
        // ChequeMapper hands lease.getGracePeriodDays() to ChequeDueRules, so this is
        // the window that decides whether the row is already showing as overdue to
        // the clerk chasing it. RentCollectionSettings has a grace column too and it
        // is a different, property-wide default — proposing a fine off one number
        // while the screen flags lateness by another is how a renter gets charged for
        // a day the register never called late.
        int grace = lease.getGracePeriodDays();
        LocalDate effectiveDue = cheque.getChequeDate().plusDays(grace);
        if (!clearedOn.isAfter(effectiveDue)) return;

        long daysLate = ChronoUnit.DAYS.between(effectiveDue, clearedOn);
        BigDecimal amount = lateAmount(cheque.getAmount(), settings, daysLate);
        if (amount == null || amount.signum() <= 0) {
            log.info("No late-payment penalty proposed for cheque {}: {} days late computes to {}",
                    cheque.getId(), daysLate, amount);
            return;
        }

        if (assessments.existsByCheque_IdAndReasonAndStatusIn(
                cheque.getId(), PenaltyReason.LATE_PAYMENT, PenaltyAssessmentService.OPEN)) {
            return;
        }

        assessmentService.proposeBySystem(lease, cheque, PenaltyReason.LATE_PAYMENT, amount,
                "Cheque " + label(cheque) + " cleared " + daysLate + " day" + (daysLate == 1 ? "" : "s")
                        + " after its grace period (due " + effectiveDue + ", cleared " + clearedOn + ")");
    }

    /**
     * What lateness costs, by the property's rule.
     *
     * <p>Copied verbatim from {@code PenaltyCalculationService.calculatePenalty} —
     * including the percentage branch rounding to 2 decimals <em>before</em> it
     * multiplies by the days, which changes the answer and is therefore part of the
     * formula rather than an implementation detail. {@code PenaltyRuleEngineTest}
     * pins both against each other so the copy cannot drift before the v1 class is
     * deleted.</p>
     *
     * @param amount the instrument's own amount — the base a percentage is of.
     * @param daysLate whole days past the effective due date; never negative.
     */
    public static BigDecimal lateAmount(BigDecimal amount, RentCollectionSettings settings, long daysLate) {
        if (settings == null || settings.getPenaltyType() == null
                || settings.getPenaltyType() == PenaltyType.NONE
                || settings.getPenaltyAmount() == null
                || daysLate <= 0) {
            return BigDecimal.ZERO;
        }
        return switch (settings.getPenaltyType()) {
            case FIXED_PER_DAY -> settings.getPenaltyAmount().multiply(BigDecimal.valueOf(daysLate));
            case PERCENTAGE -> amount == null ? BigDecimal.ZERO
                    : amount.multiply(settings.getPenaltyAmount())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(daysLate));
            default -> BigDecimal.ZERO;
        };
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    /** The row's own property, else the lease's — the register denormalises it, older rows may not. */
    private static UUID propertyIdOf(Cheque cheque, Lease lease) {
        if (cheque == null) return null;
        Property onRow = cheque.getProperty();
        if (onRow != null) return onRow.getId();
        Unit unit = lease != null ? lease.getUnit() : null;
        Property viaLease = unit != null ? unit.getProperty() : null;
        return viaLease != null ? viaLease.getId() : null;
    }

    private static String label(Cheque c) {
        return c.getChequeNumber() != null && !c.getChequeNumber().isBlank()
                ? c.getChequeNumber() : "row " + c.getSeqNo();
    }
}
