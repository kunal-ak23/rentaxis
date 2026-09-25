package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * #55: a VAT return period marked filed. {@code boxes} keeps the figures as filed
 * (JSON of the return's boxes). While FILED, VAT postings dated in the period are
 * refused (PostingService); corrections are dated in an open period and show on
 * that period's return. A re-open leaves the row REOPENED as the audit trail.
 */
@Entity
@Table(name = "vat_returns")
@Getter
@Setter
public class VatReturn extends BaseTenantEntity {

    public static final String FILED = "FILED";
    public static final String REOPENED = "REOPENED";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(nullable = false, length = 12)
    private String status = FILED;

    @Column(columnDefinition = "text")
    private String boxes;

    @Column(name = "net_vat", precision = 14, scale = 2)
    private BigDecimal netVat;

    @Column(name = "filing_reference", length = 100)
    private String filingReference;

    @Column(name = "filed_by")
    private UUID filedBy;

    @Column(name = "filed_at", nullable = false)
    private Instant filedAt;

    @Column(name = "reopened_by")
    private UUID reopenedBy;

    @Column(name = "reopened_at")
    private Instant reopenedAt;

    @Column(name = "reopen_reason", length = 500)
    private String reopenReason;
}
