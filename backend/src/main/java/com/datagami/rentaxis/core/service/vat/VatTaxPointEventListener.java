package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.core.service.lease.LeaseAmendedEvent;
import com.datagami.rentaxis.core.service.lease.LeaseExtendedEvent;
import com.datagami.rentaxis.core.service.lease.LeasePostedEvent;
import com.datagami.rentaxis.core.service.lease.LeaseVariedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the VAT schedule in step with the register (spec 2026-09-24 §1), the way
 * {@code RecognitionEventListener} keeps the recognition schedule in step with the
 * lines.
 *
 * <p><b>Same transaction, deliberately</b>, for the reason that class gives: a lease
 * must never reach the books with VAT parked in {@code OUTPUT_VAT_DEFERRED} and no
 * tax point to move it out, and a failure here fails the post. Every event calls
 * the same idempotent build: it gives each VAT-bearing row that has no live point
 * one — the new rows of an extension or addendum, and after an amendment (which
 * cancelled the old PLANNED points) every row again.</p>
 */
@Component
public class VatTaxPointEventListener {

    private final VatTaxPointService vatTaxPoints;

    public VatTaxPointEventListener(VatTaxPointService vatTaxPoints) {
        this.vatTaxPoints = vatTaxPoints;
    }

    @EventListener
    public void onLeasePosted(LeasePostedEvent event) {
        vatTaxPoints.buildForLease(event.leaseId());
    }

    @EventListener
    public void onLeaseAmended(LeaseAmendedEvent event) {
        vatTaxPoints.buildForLease(event.leaseId());
    }

    @EventListener
    public void onLeaseExtended(LeaseExtendedEvent event) {
        vatTaxPoints.buildForLease(event.leaseId());
    }

    @EventListener
    public void onLeaseVaried(LeaseVariedEvent event) {
        vatTaxPoints.buildForLease(event.leaseId());
    }
}
