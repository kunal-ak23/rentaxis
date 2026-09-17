package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/** The tenant's recipe for the per-property leaves created when a property is onboarded. Spec §5.1. */
@Entity
@Table(name = "property_account_template_rows")
@Getter
@Setter
public class PropertyAccountTemplateRow extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccountRole role;

    /** e.g. "Rent Receivable - {property}"; {property} is replaced with Property.nameEn. */
    @Column(name = "name_pattern", nullable = false)
    private String namePattern;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "parent_account_id", nullable = false)
    private Account parentAccount;

    @Column(nullable = false)
    private boolean enabled = true;
}
