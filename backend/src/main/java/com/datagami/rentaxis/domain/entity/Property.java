package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PropertyType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "properties")
@Getter
@Setter
public class Property extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "name_en", nullable = false)
    private String nameEn;

    @Column(name = "name_ar")
    private String nameAr;

    /**
     * Short building code the landlord uses on paper — "GLA_B1". Prefixed to the
     * contract number on documents ("GLA_B1/681") so a number is unambiguous
     * across a portfolio. Nullable, and unique per tenant when present
     * ({@code ux_properties_tenant_code}, changeset 83); a duplicate surfaces as
     * a 409 through {@code GlobalExceptionHandler}.
     */
    @Column(length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Emirate emirate;

    private String address;

    @Column(name = "makani_number")
    private String makaniNumber;

    @Enumerated(EnumType.STRING)
    private PropertyType type = PropertyType.RESIDENTIAL;

    @Column(name = "fixed_expenses")
    private BigDecimal fixedExpenses = BigDecimal.ZERO;
}
