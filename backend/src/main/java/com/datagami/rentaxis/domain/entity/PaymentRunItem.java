package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One invoice (a PISR) or opening item a payment run pays, and how much.
 * {@code applyAdvance}: the vendor's unallocated advance is applied first (a
 * vendor-level choice, stored on each of its items). {@code bpvId}: the
 * vendor's voucher, once the run is posted.
 */
@Entity
@Table(name = "payment_run_items")
@Getter
@Setter
public class PaymentRunItem extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "run_id", nullable = false) private UUID runId;
    @Column(name = "vendor_id", nullable = false) private UUID vendorId;
    @Column(name = "invoice_voucher_id") private UUID invoiceVoucherId;
    @Column(name = "opening_item_id") private UUID openingItemId;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "apply_advance", nullable = false) private boolean applyAdvance = true;
    @Column(name = "bpv_id") private UUID bpvId;
}
