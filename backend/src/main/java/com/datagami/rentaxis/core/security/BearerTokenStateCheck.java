package com.datagami.rentaxis.core.security;

import java.util.UUID;

/**
 * The per-request "is this still a good token" question {@link ApiSecurityFilter}
 * asks after the signature checks out. Production answer:
 * {@link TokenRevocationService}; an interface so the filter's unit tests can
 * run without a database.
 */
@FunctionalInterface
public interface BearerTokenStateCheck {

    /**
     * @param activeTenantId the tenant the request will act in (header or home
     *                       tenant), or null when there is none
     * @return null when the token may be used, else a short reason for the log
     */
    String rejectionReason(AuthTokenService.VerifiedIdentity identity, UUID activeTenantId);
}
