package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.FiscalYearCloseStatus;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One close of one fiscal year (spec 2026-09-24 §3). At most one CLOSED row per
 * tenant and year (partial unique index); a re-open leaves the row REOPENED as
 * the audit trail, and a later close writes a new one.
 */
@Entity
@Table(name = "fiscal_year_closes")
@Getter
@Setter
public class FiscalYearClose extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** The label {@code fiscalYearOf} gives: the calendar year the fiscal year starts in. */
    @Column(name = "fiscal_year", nullable = false)
    private int fiscalYear;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private FiscalYearCloseStatus status = FiscalYearCloseStatus.CLOSED;

    /** The YEC, or null when the year had no income or expense to close. */
    @Column(name = "journal_id")
    private UUID journalId;

    /** The period lock before this close moved it (restored in spirit by a re-open). */
    @Column(name = "lock_before")
    private LocalDate lockBefore;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(name = "reopened_by")
    private UUID reopenedBy;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopen_reason", length = 500)
    private String reopenReason;
}
