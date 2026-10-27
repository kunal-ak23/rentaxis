package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoRedirectSource;

/**
 * One query parameter of a coupon ad's redirect link (plan addendum A):
 * {@code name=<value from source>}. {@code value} is set only for
 * {@link PromoRedirectSource#STATIC}. Stored as a jsonb array on promo_ads and
 * returned as is in the admin and promoter ad DTOs.
 */
public record PromoRedirectParam(String name, PromoRedirectSource source, String value) {
}
