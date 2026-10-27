package com.datagami.rentaxis.domain.entity.enums;

public enum UserRole {
    SUPER_ADMIN,
    TENANT_ADMIN,
    PROPERTY_MANAGER,
    ACCOUNTANT,
    TENANT_USER,
    RENTER,
    SECURITY_GUARD,
    /**
     * An outside business's login that manages only the promo businesses and ads
     * an admin granted it (changeset 183). Invited from Promotions, never from
     * Users; belongs to exactly one organisation; fenced off every API path but
     * its own ({@code PromoterFenceFilter}).
     */
    PROMOTER
}
