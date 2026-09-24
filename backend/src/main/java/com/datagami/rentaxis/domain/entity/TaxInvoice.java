package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A numbered tax invoice (or tax credit note) issued on a VAT tax point — product
 * decision 2026-09-24, spec §1.
 *
 * <p><b>A snapshot, not a view.</b> Supplier name and TRN, the renter's name, the
 * property and unit are copied in at issue, because a tax invoice is the document
 * that was issued: renaming a building next year must not rewrite an invoice a
 * renter already holds. The table is append-only in the database (changeset 108's
 * trigger), with the tenant-purge exemption changeset 89 gives the journal.</p>
 *
 * <p>Numbered {@code TI-yy/n} (credit notes {@code TCN-yy/n}) per tenant per fiscal
 * year of the issue date, through the same row-locked sequence journal numbers use,
 * inside the transaction that posts the tax point — so a rolled-back post releases
 * its number and the series stays gapless.</p>
 */
@Entity
@Table(name = "tax_invoices")
@Getter
@Setter
public class TaxInvoice extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, length = 40, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TaxInvoiceKind kind;

    @Column(name = "tax_point_id", nullable = false, updatable = false)
    private UUID taxPointId;

    @Column(name = "journal_id", updatable = false)
    private UUID journalId;

    @Column(name = "lease_id", nullable = false, updatable = false)
    private UUID leaseId;

    @Column(name = "renter_id", updatable = false)
    private UUID renterId;

    @Column(name = "property_id", updatable = false)
    private UUID propertyId;

    @Column(name = "unit_id", updatable = false)
    private UUID unitId;

    @Column(name = "cheque_id", updatable = false)
    private UUID chequeId;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "period_start", updatable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", updatable = false)
    private LocalDate periodEnd;

    @Column(name = "supplier_name", nullable = false, updatable = false)
    private String supplierName;

    @Column(name = "supplier_trn", nullable = false, length = 50, updatable = false)
    private String supplierTrn;

    @Column(name = "supplier_address", columnDefinition = "text", updatable = false)
    private String supplierAddress;

    @Column(name = "customer_name", updatable = false)
    private String customerName;

    @Column(name = "customer_name_ar", updatable = false)
    private String customerNameAr;

    @Column(name = "customer_trn", length = 50, updatable = false)
    private String customerTrn;

    @Column(name = "property_name", updatable = false)
    private String propertyName;

    @Column(name = "unit_number", length = 100, updatable = false)
    private String unitNumber;

    @Column(columnDefinition = "text", updatable = false)
    private String description;

    /** Positive on an invoice, positive on a credit note too — the kind says which way. */
    @Column(name = "taxable_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal taxableAmount;

    @Column(name = "vat_rate", nullable = false, precision = 5, scale = 4, updatable = false)
    private BigDecimal vatRate;

    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
