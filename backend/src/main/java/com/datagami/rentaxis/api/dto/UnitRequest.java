package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/v1/units}.
 *
 * <p>The endpoint used to bind the {@code Unit} entity. {@code Property.id} is
 * writable to Jackson, so {@code property: {id}} became a stub that Hibernate
 * wrote as a foreign key with no tenant check: a tenant admin could create a
 * unit on another tenant's property (PR #340 review, C1). This record carries
 * scalars and ids only, and {@code UnitService} resolves the ids in the
 * caller's tenant.</p>
 *
 * <p>The web and mobile forms' shape ({@code property: {id}},
 * {@code building: {id}}) is accepted unchanged; anything else is ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnitRequest(
        String unitNumber,
        UnitType type,
        BigDecimal sizeSqft,
        UnitStatus status,
        BigDecimal expectedRent,
        BigDecimal actualRent,
        String currentTenantName,
        IdRef property,
        IdRef building) {
}
