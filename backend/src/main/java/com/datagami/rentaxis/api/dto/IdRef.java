package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * A reference to an existing row by id, in the nested shape the web and mobile
 * forms already send ({@code property: {id}}).
 *
 * <p>Request records carry these instead of JPA entities. An entity stub with a
 * client-chosen id is written by Hibernate as a foreign key with no tenant check,
 * which is how a unit, building or staff row came to point at another tenant's
 * property. A service resolves each {@code IdRef} to a managed row in the
 * caller's tenant before anything is saved ({@code TenantReferences}).</p>
 *
 * <p>Anything else inside the object (a client echoing back a whole GET
 * response) is ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdRef(UUID id) {}
