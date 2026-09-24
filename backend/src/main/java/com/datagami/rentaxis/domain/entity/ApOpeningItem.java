package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A supplier invoice still open at cut-over (finance-ops spec §2). Its balance
 * arrived on the vendor's payable leaf as an OB line; this row makes it an open
 * item that payments can be allocated to, like a PISR.
 */
@Entity
@Table(name = "ap_opening_items")
@Getter
@Setter
public class ApOpeningItem extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "vendor_id", nullable = false) private UUID vendorId;
    @Column(name = "invoice_number", nullable = false, length = 60) private String invoiceNumber;
    @Column(name = "invoice_date", nullable = false) private LocalDate invoiceDate;
    @Column(name = "due_date", nullable = false) private LocalDate dueDate;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "property_id") private UUID propertyId;
    /**
     * Set on the item a cancelled cut-over cheque leaves on its vendor (PR #352
     * re-review R2, changeset 113). Such an item is generated, not typed: it stays
     * out of the OB tie-out and cannot be edited or deleted by hand.
     */
    @Column(name = "issued_cheque_id") private UUID issuedChequeId;

    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;
}
