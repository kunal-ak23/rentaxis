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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Emirate emirate;

    private String address;

    @Column(name = "makani_number")
    private String makaniNumber;

    @Enumerated(EnumType.STRING)
    private PropertyType type = PropertyType.RESIDENTIAL;

    @Column(name = "property_manager")
    private String propertyManager;

    @Column(name = "fixed_expenses")
    private BigDecimal fixedExpenses = BigDecimal.ZERO;
}
