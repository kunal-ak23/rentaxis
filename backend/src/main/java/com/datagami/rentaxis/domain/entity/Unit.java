package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "units")
@Getter
@Setter
public class Unit extends BaseTenantEntity {

    // Serialized in responses, never accepted from a request body.
    //
    // These six endpoints bind the JPA entity directly as the request DTO, so
    // every settable property was client-writable. A POST carrying an id made
    // Hibernate treat repository.save() as an update to that row rather than an
    // insert, turning "create" into "silently overwrite something else in my
    // tenant". READ_ONLY closes that without changing any response shape.
    @com.fasterxml.jackson.annotation.JsonProperty(
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id")
    private Building building;

    @Column(name = "unit_number", nullable = false)
    private String unitNumber;

    @Enumerated(EnumType.STRING)
    private UnitType type = UnitType.BHK1;

    @Column(name = "size_sqft")
    private BigDecimal sizeSqft;

    @Enumerated(EnumType.STRING)
    private UnitStatus status = UnitStatus.VACANT;

    @Column(name = "expected_rent")
    private BigDecimal expectedRent = BigDecimal.ZERO;

    @Column(name = "actual_rent")
    private BigDecimal actualRent = BigDecimal.ZERO;

    @Column(name = "current_tenant_name")
    private String currentTenantName;

    @Version
    private Long version;
}
