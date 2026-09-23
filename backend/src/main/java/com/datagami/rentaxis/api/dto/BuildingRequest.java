package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for {@code POST /api/v1/buildings}.
 *
 * <p>The endpoint used to bind the {@code Building} entity, whose
 * {@code property: {id}} stub Hibernate wrote as a foreign key with no tenant
 * check, so a building could be created on another tenant's property (PR #340
 * review, C2). {@code BuildingService} now resolves the id in the caller's
 * tenant. The web form's shape is accepted unchanged; anything else is ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildingRequest(String nameEn, String nameAr, Integer floors, IdRef property) {
}
