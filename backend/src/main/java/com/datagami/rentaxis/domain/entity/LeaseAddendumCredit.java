package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line a credit addendum cut (F14-32): the line's remaining value from the
 * addendum's effective date before and after, the credit that took it down, and
 * the VAT on that credit. Immutable once written.
 */
@Entity
@Table(name = "lease_addendum_credits")
@Getter
@Setter
public class LeaseAddendumCredit extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "addendum_id", nullable = false)
    private UUID addendumId;

    @Column(name = "lease_line_id", nullable = false)
    private UUID leaseLineId;

    /** The line's value over its whole window at the new rate (0 = removed). */
    @Column(name = "new_line_amount", nullable = false)
    private BigDecimal newLineAmount;

    @Column(name = "remaining_before", nullable = false)
    private BigDecimal remainingBefore;

    @Column(name = "remaining_after", nullable = false)
    private BigDecimal remainingAfter;

    @Column(name = "credit_amount", nullable = false)
    private BigDecimal creditAmount;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount;
}
