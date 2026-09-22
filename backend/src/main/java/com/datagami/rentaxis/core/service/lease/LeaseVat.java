package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * VAT on a lease line — the one place the 5% is applied (spec §6.2).
 *
 * <p>It exists because the same number is computed in three places that must
 * agree to the fils: the contract PDF prints it, the cheque grid collects it, and
 * Task 6's posting journal credits it. When the grid rounded its own way the
 * cheques came to a different total from the contract the renter had signed, and
 * the difference showed up as an unexplained balance rather than as an error.</p>
 *
 * <p><b>Per line, then summed — never the other way round.</b> VAT is rounded to
 * two decimals on each line, so Σ(round(line)) is the total, not
 * round(Σ(lines)). The two differ by a fils on some line counts, and the invoice
 * the renter holds is the per-line one.</p>
 *
 * <p><b>A DEPOSIT-behaviour line never carries VAT</b>, whatever its own
 * {@code vatApplicable} flag says. A deposit is refundable money held against the
 * tenancy, not consideration for a supply, so there is nothing to tax; it is
 * refunded at settlement at the figure it was collected at. This used to be
 * defined twice — here the flag was taken at its word and in the contract PDF's
 * {@code vatOn} deposits were forced to zero — so a lease with a VAT-flagged
 * deposit would have printed no VAT on the contract the renter signed and
 * collected it on the cheques anyway. One definition, applied here, and the
 * contract, the cheque grid and the posting journal all follow it.</p>
 */
public final class LeaseVat {

    /** UAE standard rate. Not configurable: a change is a tax event, not a setting. */
    public static final BigDecimal RATE = new BigDecimal("0.05");

    private LeaseVat() {
    }

    /** VAT on an amount, to the fils. Null and negative-free: a null net is no VAT. */
    static BigDecimal vatOf(BigDecimal net) {
        if (net == null) return BigDecimal.ZERO;
        return net.multiply(RATE).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * VAT on a line that does not exist yet — the cut-over import's validator, which
     * has a spreadsheet row rather than a {@link LeaseLine} and still has to arrive
     * at the same figure (review R5).
     *
     * <p>It is the primitive the rest of this class is written in, not a parallel
     * implementation: {@link #vatOf(LeaseLine)} calls it with the line's own three
     * facts. That matters because the number it produces is compared for exact
     * equality against a cheque grid at import time and again at posting time, and
     * a second copy of "a deposit is never taxed" would let a workbook pass import
     * and fail post — which is precisely the defect the pre-flight found in the
     * brief's net-only rule.</p>
     *
     * @param behaviour the charge type's behaviour, or null when it has none.
     */
    public static BigDecimal vatOfNet(BigDecimal net, boolean vatApplicable, ChargeBehaviour behaviour) {
        return vatApplicable && behaviour != ChargeBehaviour.DEPOSIT ? vatOf(net) : BigDecimal.ZERO;
    }

    private static ChargeBehaviour behaviourOf(LeaseLine line) {
        return line.getChargeType() == null ? null : line.getChargeType().getBehaviour();
    }

    /**
     * Whether this line is taxed at all: VAT-applicable, and not a DEPOSIT (see the
     * class note). The single definition every method here and every caller
     * outside it agrees on.
     */
    private static boolean carriesVat(LeaseLine line) {
        if (line == null || !line.isVatApplicable()) return false;
        return behaviourOf(line) != ChargeBehaviour.DEPOSIT;
    }

    /**
     * VAT the line carries: zero when the line is not VAT-applicable, and zero for
     * a DEPOSIT-behaviour line whatever its flag says (see the class note).
     *
     * <p>Public rather than package-private so {@code ContractGenerationService},
     * which lives one package up, can ask the same question instead of keeping its
     * own copy of the deposit rule.</p>
     */
    public static BigDecimal vatOf(LeaseLine line) {
        if (line == null) return BigDecimal.ZERO;
        return vatOfNet(line.getNetAmount(), line.isVatApplicable(), behaviourOf(line));
    }

    /**
     * VAT on <em>part</em> of a line's net amount — what a termination credits back
     * for the rent it un-earns (spec §9.1, review I-5).
     *
     * <p>The {@code TCO} debits the receivable with the rent <em>and</em> the tax on
     * it. Cutting the term short un-earns some of that rent, and the tax on a supply
     * that never happened is neither the landlord's to keep nor the Authority's to
     * be paid: the {@code TCR} hands it back on the same journal, as a credit note.
     * Without it the receivable carries 5% of rent the renter never used and the
     * settlement collects it.</p>
     *
     * <p><b>Whether</b> there is any VAT is decided by exactly the same rule as
     * {@link #vatOf(LeaseLine)} — only the base changes, from the whole line to the
     * unearned slice — so the credit note and the original charge cannot be computed
     * two different ways. Rounded once, on the portion, for the same per-line reason
     * the class note gives.</p>
     *
     * <p>A null line (an amendment deleted it out from under a retired segment; see
     * changeset 86) carries no VAT, which is also what a null line's original charge
     * would compute to.</p>
     */
    public static BigDecimal vatOnPortion(LeaseLine line, BigDecimal portion) {
        if (!carriesVat(line) || portion == null || portion.signum() <= 0) return BigDecimal.ZERO;
        return vatOf(portion);
    }

    /** What the line is actually collected for: net plus its own VAT. */
    static BigDecimal grossOf(LeaseLine line) {
        BigDecimal net = line == null || line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
        return net.add(vatOf(line));
    }
}
