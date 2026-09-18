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
     * VAT the line carries: zero when the line is not VAT-applicable, and zero for
     * a DEPOSIT-behaviour line whatever its flag says (see the class note).
     *
     * <p>Public rather than package-private so {@code ContractGenerationService},
     * which lives one package up, can ask the same question instead of keeping its
     * own copy of the deposit rule.</p>
     */
    public static BigDecimal vatOf(LeaseLine line) {
        if (line == null || !line.isVatApplicable()) return BigDecimal.ZERO;
        if (line.getChargeType() != null && line.getChargeType().getBehaviour() == ChargeBehaviour.DEPOSIT) {
            return BigDecimal.ZERO;
        }
        return vatOf(line.getNetAmount());
    }

    /** What the line is actually collected for: net plus its own VAT. */
    static BigDecimal grossOf(LeaseLine line) {
        BigDecimal net = line == null || line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
        return net.add(vatOf(line));
    }
}
