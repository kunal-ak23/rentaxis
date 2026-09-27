package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * S16-14 (#376 P1-1): the VAT on a lease that came in with an acquired building.
 *
 * <p>The previous owner declared the contract's Output VAT (and paid it, under their
 * TRN). Handing any of it back — a termination, a credit addendum, a transfer out, an
 * amendment that lowers the VAT — is theirs to refund: it goes to the property's
 * vendor account (ACQUISITION_CLEARING), never through our Output VAT, and carries no
 * credit note of ours. VAT we declare after the acquisition (an amendment that raises
 * the VAT, an addendum we invoice) is ours and follows the normal rules.</p>
 *
 * <p><b>Which part of a decrease is the vendor's.</b> Our own contract-VAT documents
 * on the lease (tax invoices less credit notes of kind CONTRACT, AMENDMENT, REDUCTION
 * and TERMINATION_ADJUSTMENT) are handed back first — the latest VAT is ours — and the
 * rest comes out of {@code leases.acquired_vat_open}, the vendor's VAT still standing.</p>
 */
@Component
public class AcquiredLeaseVat {

    private final LeaseRepository leases;
    private final NamedParameterJdbcTemplate jdbc;

    public AcquiredLeaseVat(LeaseRepository leases, NamedParameterJdbcTemplate jdbc) {
        this.leases = leases;
        this.jdbc = jdbc;
    }

    /** The part of a VAT decrease of {@code vat} on this lease that is the vendor's; nothing written. */
    public BigDecimal vendorPart(Lease lease, BigDecimal vat) {
        if (lease == null || lease.getAcquiredOn() == null || vat == null || vat.signum() <= 0) return zero();
        BigDecimal open = lease.getAcquiredVatOpen() == null ? BigDecimal.ZERO : lease.getAcquiredVatOpen();
        if (open.signum() <= 0) return zero();
        BigDecimal ours = oursStanding(lease).max(BigDecimal.ZERO);
        return vat.subtract(ours).max(BigDecimal.ZERO).min(open).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@link #vendorPart}, and takes it off the vendor's VAT still standing on the lease. */
    public BigDecimal takeVendorPart(Lease lease, BigDecimal vat) {
        BigDecimal part = vendorPart(lease, vat);
        if (part.signum() > 0) {
            lease.setAcquiredVatOpen(lease.getAcquiredVatOpen().subtract(part));
            leases.save(lease);
        }
        return part;
    }

    /** Our own contract-VAT documents on the lease: tax invoices less credit notes. */
    private BigDecimal oursStanding(Lease lease) {
        BigDecimal v = jdbc.queryForObject("""
                select coalesce(sum(case when i.kind = 'CREDIT_NOTE' then -i.vat_amount else i.vat_amount end), 0)
                  from tax_invoices i join vat_tax_points p on p.id = i.tax_point_id and p.tenant_id = :t
                 where i.tenant_id = :t and i.lease_id = :l
                   and p.kind in ('CONTRACT', 'AMENDMENT', 'REDUCTION', 'TERMINATION_ADJUSTMENT')""",
                new MapSqlParameterSource("t", TenantContextHolder.getTenantId()).addValue("l", lease.getId()),
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
