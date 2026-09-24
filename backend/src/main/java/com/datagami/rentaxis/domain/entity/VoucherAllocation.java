package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Which supplier invoice (a PISR, or an opening item) a payment (BPV) settles —
 * finance-ops spec §2. A sub-ledger index, never a journal writer: the BPV has
 * already moved the vendor's payable leaf. Written only by
 * {@code VoucherAllocationService}.
 *
 * <p>Live from {@code allocatedOn} until {@code releasedOn}, so aging as of any
 * date is reproducible. A release never deletes the row.</p>
 */
@Entity
@Table(name = "voucher_allocations")
@Getter
@Setter
public class VoucherAllocation extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "vendor_id", nullable = false) private UUID vendorId;
    @Column(name = "payment_voucher_id", nullable = false) private UUID paymentVoucherId;
    @Column(name = "invoice_voucher_id") private UUID invoiceVoucherId;
    @Column(name = "opening_item_id") private UUID openingItemId;

    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;

    @Column(name = "allocated_on", nullable = false) private LocalDate allocatedOn;
    @Column(name = "released_on") private LocalDate releasedOn;
    @Column(name = "release_reason", columnDefinition = "text") private String releaseReason;

    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "released_by") private UUID releasedBy;

    public boolean isLive() { return releasedOn == null; }
}
