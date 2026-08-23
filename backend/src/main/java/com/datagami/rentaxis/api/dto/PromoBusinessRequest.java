package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** category null = OTHER; active null = true; allowedDomains null = none allowed. */
public record PromoBusinessRequest(
        @NotBlank @Size(max = 160) String nameEn,
        @Size(max = 160) String nameAr,
        @Size(max = 512) String logoUrl,
        PromoCategory category,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "phone must be E.164, e.g. +971501234567")
        String phoneE164,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "whatsapp must be E.164, e.g. +971501234567")
        String whatsappE164,
        List<String> allowedDomains,
        Boolean active) {
}
