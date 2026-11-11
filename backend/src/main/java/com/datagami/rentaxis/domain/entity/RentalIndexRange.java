package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.UnitType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One DLD rental index range as a manager read it off the calculator: the AED/year
 * band for a building (our property) × bedroom category (our {@link UnitType}) ×
 * contract-end year. The renewal preview turns it into a Decree 43 cap
 * ({@code Decree43}); nothing else reads it.
 */
@Entity
@Table(name = "rental_index_ranges")
@Getter
@Setter
public class RentalIndexRange extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false, length = 20)
    private UnitType unitType;

    @Column(name = "index_year", nullable = false)
    private int indexYear;

    @Column(name = "min_value", nullable = false)
    private BigDecimal minValue;

    @Column(name = "max_value", nullable = false)
    private BigDecimal maxValue;

    @Column(name = "source_note", length = 500)
    private String sourceNote;

    @Column(name = "entered_by")
    private UUID enteredBy;

    @Column(name = "entered_at", nullable = false)
    private Instant enteredAt = Instant.now();
}
