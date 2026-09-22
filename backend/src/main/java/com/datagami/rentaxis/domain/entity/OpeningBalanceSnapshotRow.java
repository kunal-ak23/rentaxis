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
 * One line of the PACT trial balance as at (books_start_date − 1), keyed by PACT's
 * account code (changeset 88). This is both the upload target and the store the grid
 * edits, so the reconciliation screen can be re-run at any time against the same
 * figures the OB journal was built from (spec §10.3).
 *
 * <p><b>Keyed by code, not by account id</b>, so a trial balance can be uploaded
 * before every account exists in our chart — codes we do not have are reported on
 * the reconciliation screen rather than dropped on import. {@code
 * ux_opening_balance_snapshots_tenant_code} makes the code unique per tenant, and
 * {@code ck_ob_snapshots_one_side} enforces that only one of the two columns is
 * filled, which is why the parser nets a two-sided row down before it gets here.</p>
 */
@Entity
@Table(name = "opening_balance_snapshots")
@Getter
@Setter
public class OpeningBalanceSnapshotRow extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "account_code", nullable = false, length = 40)
    private String accountCode;

    @Column(name = "account_name")
    private String accountName;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "uploaded_at") private Instant uploadedAt = Instant.now();
    @Column(name = "uploaded_by") private UUID uploadedBy;
}
