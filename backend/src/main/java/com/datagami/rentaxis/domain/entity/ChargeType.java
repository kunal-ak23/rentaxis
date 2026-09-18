package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
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

import java.util.UUID;

/**
 * A particular a lease can be charged for — the tenant's editable catalogue behind
 * lease lines (spec §6.1). Seeded with the PACT particulars; tenants may add their own.
 *
 * <p>{@code role} is the <em>credit</em> side of the line, not a category label: a
 * RENT line credits {@code ADVANCE_RENT} (unearned rent), a deposit credits the
 * matching liability, a fee credits income. {@code behaviour} is what happens to
 * that credit afterwards. {@code ChargeTypeService} enforces that the two agree.</p>
 *
 * <p>Uniqueness of {@code (tenant_id, code)} lives in the database
 * ({@code uq_charge_types_tenant_code}, changeset 83) rather than in a
 * read-then-write check, so two concurrent creates cannot both win.</p>
 */
@Entity
@Table(name = "charge_types")
@Getter
@Setter
public class ChargeType extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "name_en", nullable = false, length = 120)
    private String nameEn;

    @Column(name = "name_ar", length = 120)
    private String nameAr;

    /** The account role the line's net amount is credited to. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccountRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargeBehaviour behaviour;

    /** Default only: the lease line carries its own {@code vatApplicable} once created. */
    @Column(name = "vat_applicable_default", nullable = false)
    private boolean vatApplicableDefault = false;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;
}
