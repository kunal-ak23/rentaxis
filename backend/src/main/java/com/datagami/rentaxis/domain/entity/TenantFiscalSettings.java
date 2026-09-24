package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One row per tenant. Not a BaseTenantEntity: tenant_id IS the primary key and the row is looked up explicitly. */
@Entity
@Table(name = "tenant_fiscal_settings")
@Getter
@Setter
public class TenantFiscalSettings {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private int fiscalYearStartMonth = 1;

    @Column(name = "books_start_date")
    private LocalDate booksStartDate;

    @Column(name = "books_locked_through")
    private LocalDate booksLockedThrough;

    /** Next numeric account code to hand out; initialised lazily from max(numeric code)+1. */
    @Column(name = "next_account_code")
    private Long nextAccountCode;

    /**
     * The tenant has started reconciling a bank account (finance-ops spec §4):
     * until then PostingService takes no bank lock. Read-only here and written by
     * BankReconciliationService in SQL, so a save of this row for another reason
     * (the account-code counter) can never write a stale false over it.
     */
    @Column(name = "bank_rec_started", nullable = false, insertable = false, updatable = false)
    private boolean bankRecStarted;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
