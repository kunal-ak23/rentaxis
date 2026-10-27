package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a coupon redirect parameter's value comes from (plan addendum A). The
 * renter-data sources (name, phone, email, property, unit) are what the renter
 * app asks consent for before opening the link; COUPON_CODE and STATIC are not
 * renter data.
 */
public enum PromoRedirectSource {
    COUPON_CODE,
    RENTER_NAME,
    RENTER_PHONE,
    RENTER_EMAIL,
    PROPERTY_NAME,
    UNIT_NUMBER,
    STATIC;

    /** Whether the value is the renter's own data, shown on the consent sheet. */
    public boolean isRenterData() {
        return switch (this) {
            case RENTER_NAME, RENTER_PHONE, RENTER_EMAIL, PROPERTY_NAME, UNIT_NUMBER -> true;
            case COUPON_CODE, STATIC -> false;
        };
    }
}
