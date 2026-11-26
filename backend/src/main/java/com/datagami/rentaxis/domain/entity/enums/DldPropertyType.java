package com.datagami.rentaxis.domain.entity.enums;

import java.util.Locale;

/** DLD's property type on a rental index result. */
public enum DldPropertyType {
    APARTMENT, VILLA, HOTEL_APARTMENT;

    /** DLD's wording ("Apartment", "Villa", "Hotel Apartment"); null when absent or unknown. */
    public static DldPropertyType fromDld(String text) {
        if (text == null || text.isBlank()) return null;
        String key = text.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (DldPropertyType t : values()) {
            if (t.name().equals(key)) return t;
        }
        return null;
    }
}
