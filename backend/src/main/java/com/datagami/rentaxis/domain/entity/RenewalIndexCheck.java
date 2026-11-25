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
import java.util.UUID;

/**
 * What one renewal was shown and decided against the rental index (spec 2026-11-25 §5, §14.7). Written once in the
 * renew transaction and never changed: revoking the range later affects future renewals only. {@code baseRent},
 * {@code newRent} and {@code capRent} are on the same annual basis (§14.2).
 */
@Entity
@Table(name = "renewal_index_checks")
@Getter
@Setter
public class RenewalIndexCheck extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** The lease being renewed. */
    @Column(name = "lease_id", nullable = false, updatable = false) private UUID leaseId;
    @Column(name = "successor_lease_id", updatable = false) private UUID successorLeaseId;
    @Column(name = "rate_id", nullable = false, updatable = false) private UUID rateId;
    @Column(name = "base_rent", nullable = false, precision = 14, scale = 2, updatable = false) private BigDecimal baseRent;
    @Column(nullable = false, precision = 14, scale = 2, updatable = false) private BigDecimal average;
    @Column(name = "gap_percent", nullable = false, precision = 12, scale = 4, updatable = false) private BigDecimal gapPercent;
    @Column(name = "allowed_percent", nullable = false, updatable = false) private int allowedPercent;
    @Column(name = "cap_rent", nullable = false, precision = 14, scale = 2, updatable = false) private BigDecimal capRent;
    @Column(name = "new_rent", nullable = false, precision = 14, scale = 2, updatable = false) private BigDecimal newRent;
    @Column(name = "unit_document_path", length = 500, updatable = false) private String unitDocumentPath;
    @Column(name = "unit_dld_percent", updatable = false) private Integer unitDldPercent;
    @Column(name = "above_cap", nullable = false, updatable = false) private boolean aboveCap;
    @Column(name = "override_reason", length = 500, updatable = false) private String overrideReason;
    @Column(name = "decided_by", updatable = false) private UUID decidedBy;
    @Column(name = "decided_at", nullable = false, updatable = false) private Instant decidedAt;
}
