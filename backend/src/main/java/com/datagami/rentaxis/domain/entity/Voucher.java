package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.VoucherStatus;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A Purchase/Service Invoice (PISR) or a Bank/Cash Payment Voucher (BPV).
 * Mutable only while {@code status == DRAFT}; posting freezes it and links the
 * journal. Amending never edits a posted row — it reverses the journal, marks
 * this row REVERSED and creates a new row with {@code amendedFromId} set.
 */
@Entity
@Table(name = "vouchers")
@Getter
@Setter
public class Voucher extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 10)
    private VoucherType docType;

    @Column(name = "doc_date", nullable = false)
    private LocalDate docDate;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    /** The vendor's own invoice number, for matching against the paper. PISR only. */
    @Column(name = "invoice_number", length = 60)
    private String invoiceNumber;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;

    /** BPV: the bank or cash leaf the money leaves from. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "payment_account_id")
    private Account paymentAccount;

    @Column(name = "cheque_number", length = 50) private String chequeNumber;
    @Column(name = "cheque_date") private LocalDate chequeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private VoucherStatus status = VoucherStatus.DRAFT;

    @Column(name = "journal_id") private UUID journalId;

    /**
     * Copy of the journal's entry_number, written on post. Denormalised on purpose:
     * the voucher list is one table scan this way instead of a join per row, and a
     * journal number never changes once assigned (entries are immutable).
     */
    @Column(name = "voucher_number", length = 40) private String voucherNumber;

    @Column(name = "amended_from_id") private UUID amendedFromId;

    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<VoucherLine> lines = new ArrayList<>();

    public void replaceLines(List<VoucherLine> newLines) {
        lines.clear();
        int n = 1;
        for (VoucherLine l : newLines) {
            l.setVoucher(this);
            l.setLineNo(n++);
            lines.add(l);
        }
    }
}
