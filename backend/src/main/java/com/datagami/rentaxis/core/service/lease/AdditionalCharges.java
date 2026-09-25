package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Charges added to a lease that is already on the books: an extension's extra
 * term (spec §6.7) and an addendum's mid-term charge. Both are additive: new
 * lines inside a window, a further TCO for them, and cheques that pay for
 * exactly that.
 *
 * <p>The window is what differs. An extension's starts the day after the old end
 * date; an addendum's runs from its effective date to the lease's current end.
 * Everything else — the deposit refusal, the rent-window rule, the VAT-inclusive
 * value and the Σ-cheques check — is the same rule and lives here once.</p>
 */
@Component
class AdditionalCharges {

    /** Which act is charging, for the wording of a refusal. */
    enum Act {
        EXTENSION("an extension", "the extension"),
        ADDENDUM("an addendum", "the addendum");

        final String indefinite;
        final String definite;

        Act(String indefinite, String definite) {
            this.indefinite = indefinite;
            this.definite = definite;
        }
    }

    private final LeaseService leaseService;

    AdditionalCharges(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    /**
     * The lines with their window pinned on.
     *
     * <p>A RENT line covers the window and nothing else. Leaving it to
     * {@code LeaseService}'s defaults would give it the lease's <em>whole</em>
     * term, which per-day recognition would then charge from the original start
     * date — every month of the original term recognised a second time. A caller
     * may name a narrower window inside this one, but never one that reaches
     * outside it.</p>
     *
     * <p>A DEPOSIT line is refused outright. The deposit is held for the tenancy
     * and the tenancy has not changed; a renter who genuinely owes more deposit
     * is charged it as a separate act.</p>
     */
    List<LeaseLineInput> dated(List<LeaseLineInput> inputs, LocalDate windowStart, LocalDate windowEnd, Act act) {
        if (inputs == null || inputs.isEmpty()) {
            throw new BusinessRuleViolationException("At least one line is required");
        }
        List<LeaseLineInput> out = new ArrayList<>(inputs.size());
        int seqNo = 0;
        for (LeaseLineInput in : inputs) {
            seqNo++;
            if (in == null) {
                throw new BusinessRuleViolationException("Line " + seqNo + " is empty");
            }
            ChargeType type = leaseService.chargeTypeOf(in, seqNo);
            String where = "Line " + seqNo + " (" + type.getCode() + ")";
            if (type.getBehaviour() == ChargeBehaviour.DEPOSIT) {
                throw new BusinessRuleViolationException(where + ": " + act.indefinite
                        + " cannot charge a deposit — the tenancy's deposit is already held.");
            }
            boolean periodic = type.getBehaviour() == ChargeBehaviour.FEE
                    && type.getRecognition() == com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.RENT_LIKE;
            if (type.getBehaviour() != ChargeBehaviour.RENT && !periodic) {
                // A one-off fee carries no period; it is charged for the act itself.
                out.add(in);
                continue;
            }
            // Rent, and (F14-18) a periodic fee, are earned over the window this act
            // charges for — never the lease's whole term.
            LocalDate from = in.periodStart() != null ? in.periodStart() : windowStart;
            LocalDate to = in.periodEnd() != null ? in.periodEnd() : windowEnd;
            if (from.isBefore(windowStart) || to.isAfter(windowEnd) || to.isBefore(from)) {
                throw new BusinessRuleViolationException(where + ": " + (periodic ? "a periodic charge" : "a rent line")
                        + " must cover part of "
                        + act.definite + " (" + windowStart + " to " + windowEnd + "), not " + from + " to " + to + ".");
            }
            out.add(new LeaseLineInput(in.chargeTypeId(), in.chargeTypeCode(), in.grossAmount(),
                    in.discountAmount(), in.narration(), in.vatApplicable(), in.creditAccountId(), from, to,
                    in.addendumId()));
        }
        return out;
    }

    /**
     * What the lines charge, VAT included — the figure Σ cheques must equal.
     * Computed through {@link LeaseVat} on transient lines, the same object the
     * post's own guard measures, so the two cannot round differently.
     */
    BigDecimal valueOf(List<LeaseLineInput> dated, Lease lease) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < dated.size(); i++) {
            LeaseLineInput in = dated.get(i);
            ChargeType type = leaseService.chargeTypeOf(in, i + 1);
            BigDecimal gross = in.grossAmount() == null ? BigDecimal.ZERO : in.grossAmount();
            BigDecimal discount = in.discountAmount() == null ? BigDecimal.ZERO : in.discountAmount();

            LeaseLine probe = new LeaseLine();
            probe.setChargeType(type);
            probe.setNetAmount(gross.subtract(discount));
            probe.setVatApplicable(LeaseService.vatApplicableFor(in, type, lease.isRentVatApplicable()));
            total = total.add(LeaseVat.grossOf(probe));
        }
        return total;
    }

    /** Σ rows must equal what the lines charge; refused before anything is written. */
    void requireCovered(List<ChequeRowInput> rows, BigDecimal charged, Act act) {
        BigDecimal collected = BigDecimal.ZERO;
        for (ChequeRowInput row : rows) {
            if (row != null && row.amount() != null) collected = collected.add(row.amount());
        }
        if (collected.compareTo(charged) != 0) {
            throw new BusinessRuleViolationException("Cheque rows total " + LeasePostingService.money(collected)
                    + " but " + act.definite + " charges " + LeasePostingService.money(charged) + ".");
        }
    }
}
