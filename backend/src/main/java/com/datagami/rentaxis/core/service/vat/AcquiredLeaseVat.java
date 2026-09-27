package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.core.service.lease.LeaseVat;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * S16-14 (#376 P1-1, R1-P2-1): the VAT on a lease that came in with an acquired building.
 *
 * <p>The previous owner declared the contract's Output VAT (and paid it, under their
 * TRN). Handing any of it back — a termination, a credit addendum, a transfer out, an
 * amendment that lowers the VAT — is theirs to refund: it goes to the property's
 * vendor account (ACQUISITION_CLEARING), never through our Output VAT, and carries no
 * credit note of ours. VAT we declare ourselves (an addendum's lines, or what an
 * amendment added to the contract) is ours and follows the normal rules.</p>
 *
 * <p><b>Split by source line, pro rata to what is handed back.</b> The caller prices
 * each line's hand-back (its unearned days, or its reduction) and passes the part on
 * the <em>contract's own</em> lines ({@link #contractLine}); the vendor's share of it is
 * {@code acquired_vat_base / VAT the contract lines charge now} — 1 until an amendment
 * raises the contract's VAT, less after — capped by what the vendor still has open.
 * Every addendum line's hand-back is ours.</p>
 */
@Component
public class AcquiredLeaseVat {

    private final LeaseRepository leases;
    private final LeaseLineRepository lines;

    public AcquiredLeaseVat(LeaseRepository leases, LeaseLineRepository lines) {
        this.leases = leases;
        this.lines = lines;
    }

    /**
     * A line the previous owner's contract charged: none of an addendum's, and not an
     * extension's (whose period starts after the acquisition).
     */
    public static boolean contractLine(Lease lease, LeaseLine line) {
        if (line == null || line.getAddendumId() != null) return false;
        return line.getPeriodStart() == null || lease.getAcquiredOn() == null
                || !line.getPeriodStart().isAfter(lease.getAcquiredOn());
    }

    /** The vendor's share of the contract lines' VAT, 0 on a lease not acquired. */
    public BigDecimal vendorShare(Lease lease) {
        if (lease == null || lease.getAcquiredOn() == null || lease.getAcquiredVatBase() == null) return BigDecimal.ZERO;
        BigDecimal now = BigDecimal.ZERO;
        for (LeaseLine l : lines.findByLease_IdOrderBySeqNoAsc(lease.getId())) {
            if (contractLine(lease, l)) now = now.add(LeaseVat.vatOf(l));
        }
        if (now.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal share = lease.getAcquiredVatBase().divide(now, MathContext.DECIMAL64);
        return share.min(BigDecimal.ONE).max(BigDecimal.ZERO);
    }

    /**
     * The vendor's part of a hand-back whose contract-line part is {@code contractVat}:
     * that times {@link #vendorShare}, capped by the vendor's VAT still open. Nothing written.
     */
    public BigDecimal vendorPart(Lease lease, BigDecimal contractVat) {
        return vendorPart(lease, contractVat, vendorShare(lease));
    }

    public BigDecimal vendorPart(Lease lease, BigDecimal contractVat, BigDecimal share) {
        if (lease == null || lease.getAcquiredOn() == null || contractVat == null || contractVat.signum() <= 0) return zero();
        BigDecimal open = lease.getAcquiredVatOpen() == null ? BigDecimal.ZERO : lease.getAcquiredVatOpen();
        if (open.signum() <= 0) return zero();
        return contractVat.multiply(share).setScale(2, RoundingMode.HALF_UP).min(open);
    }

    /** Takes {@code part} off the vendor's VAT still open (a termination or credit addendum). */
    public void take(Lease lease, BigDecimal part) {
        if (part == null || part.signum() <= 0) return;
        lease.setAcquiredVatOpen(lease.getAcquiredVatOpen().subtract(part));
        leases.save(lease);
    }

    /** An amendment took {@code part} off the vendor's VAT on the contract lines: open and base both fall. */
    public void takeByAmendment(Lease lease, BigDecimal part) {
        if (part == null || part.signum() <= 0) return;
        lease.setAcquiredVatOpen(lease.getAcquiredVatOpen().subtract(part));
        lease.setAcquiredVatBase(lease.getAcquiredVatBase().subtract(part));
        leases.save(lease);
    }

    /** Σ VAT of {@code rows} on the lease's contract lines — the caller's per-line pricing. */
    public static BigDecimal onContractLines(Lease lease, List<LineVat> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (LineVat r : rows) if (contractLine(lease, r.line())) total = total.add(r.vat());
        return total;
    }

    /** One line's VAT in a hand-back. */
    public record LineVat(LeaseLine line, BigDecimal vat) { }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
