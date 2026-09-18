package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.LeaseLine;

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
 * <p>The line's own {@code vatApplicable} flag is taken at its word, whatever the
 * charge type's behaviour. A deposit is refundable money held and normally is not
 * a supply, but the flag is set per line and may have been overridden
 * deliberately; second-guessing it here would make the grid disagree with the
 * line the user actually entered. (The contract PDF's {@code vatOn} does force
 * deposits to zero — see the note in Task 5's fix-round report.)</p>
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

    /** VAT the line carries, or zero when the line is not VAT-applicable. */
    static BigDecimal vatOf(LeaseLine line) {
        if (line == null || !line.isVatApplicable()) return BigDecimal.ZERO;
        return vatOf(line.getNetAmount());
    }

    /** What the line is actually collected for: net plus its own VAT. */
    static BigDecimal grossOf(LeaseLine line) {
        BigDecimal net = line == null || line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount();
        return net.add(vatOf(line));
    }
}
