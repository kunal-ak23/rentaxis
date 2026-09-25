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

/** F14-38: money received on a written-off debt (Dr bank / Cr bad debts recovered). */
@Entity
@Table(name = "bad_debt_recoveries")
@Getter
@Setter
public class BadDebtRecovery extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "write_off_id", nullable = false) private UUID writeOffId;
    @Column(nullable = false, precision = 14, scale = 2) private BigDecimal amount;
    @Column(name = "recovered_on", nullable = false) private LocalDate recoveredOn;
    @Column(name = "account_id", nullable = false) private UUID accountId;
    @Column(length = 500) private String note;
    @Column(name = "journal_id", nullable = false) private UUID journalId;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}
