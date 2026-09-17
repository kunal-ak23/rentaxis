package com.datagami.rentaxis.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "journal_lines")
@Getter
@Setter
public class JournalLine extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    @JsonIgnore
    private JournalEntry entry;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    @JsonIgnore
    private Account account;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public UUID getAccountId() { return account == null ? null : account.getId(); }

    /**
     * The counter-account this line faces, when the posting paired it with one
     * (Addendum A). PACT prints one "Particular" per ledger row, so a TCO whose
     * receivable is split across advance rent, deposit and admin fee must name
     * a different account on each row rather than all three on all three.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contra_account_id")
    @JsonIgnore
    private Account contraAccount;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public UUID getContraAccountId() { return contraAccount == null ? null : contraAccount.getId(); }

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal debit = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
    @Column(name = "lease_id") private UUID leaseId;
    @Column(name = "renter_id") private UUID renterId;
    @Column(name = "cheque_id") private UUID chequeId;

    @Column(columnDefinition = "text")
    private String narration;
}
