package com.datagami.rentaxis.domain.entity.enums;

/**
 * Who gave notice on a tenancy (#27).
 *
 * <p>In the UAE these are different instruments with different legal weight: a
 * renter's notice that they are leaving, and a landlord's notice (for eviction
 * or non-renewal, notarised and served well ahead). The product records which
 * one it holds so it can evidence it later.</p>
 */
public enum NoticeParty {
    RENTER,
    LANDLORD
}
