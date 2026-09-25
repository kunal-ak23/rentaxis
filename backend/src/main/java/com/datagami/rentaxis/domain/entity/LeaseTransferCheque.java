package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** Spec §2: one uncleared row of the lease being left, and what the transfer does with it. */
@Entity
@Table(name = "lease_transfer_cheques")
@Getter
@Setter
public class LeaseTransferCheque extends BaseTenantEntity {

    public static final String CARRY = "CARRY";
    public static final String KEEP = "KEEP";
    public static final String RETURN = "RETURN";

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "successor_lease_id", nullable = false)
    private UUID successorLeaseId;

    @Column(name = "cheque_id", nullable = false)
    private UUID chequeId;

    @Column(name = "disposition", nullable = false, length = 8)
    private String disposition;
}
