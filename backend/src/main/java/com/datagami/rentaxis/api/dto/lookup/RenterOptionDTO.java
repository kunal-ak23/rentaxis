package com.datagami.rentaxis.api.dto.lookup;

import java.util.UUID;

/** One renter in a picker or a names lookup (scale P1-6). */
public record RenterOptionDTO(UUID id, String nameEn, String nameAr, String phone, String email) {
}
