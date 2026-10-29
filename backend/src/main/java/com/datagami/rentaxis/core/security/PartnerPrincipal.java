package com.datagami.rentaxis.core.security;

import java.security.Principal;
import java.util.UUID;

/**
 * The caller on {@code /api/v1/partner/**}: an API key, its organisation and the
 * promoter it belongs to, all from the key's stored row (never from a header). Set
 * by {@link PartnerApiKeyFilter} with the single authority
 * {@link PartnerApiKeyFilter#PARTNER_AUTHORITY}; controllers take it with
 * {@code @AuthenticationPrincipal PartnerPrincipal p}.
 *
 * <p>{@link #getName()} is deliberately not a bare user id: code elsewhere reads
 * {@code Authentication.getName()} as the signed-in user's UUID, and a key must never
 * be mistaken for a signed-in session.
 */
public record PartnerPrincipal(UUID keyId, UUID tenantId, UUID promoterUserId) implements Principal {

    @Override
    public String getName() {
        return "partner-key:" + keyId;
    }
}
