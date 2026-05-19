package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateRenterDTO {
    @NotBlank
    private String nameEn;

    private String nameAr;

    @Email
    private String email;

    private String phone;

    private Language primaryLanguage = Language.EN;

    /**
     * Whether to auto-create a portal User account for this renter, with the
     * same name + email. Defaults to true — every renter gets portal access
     * unless explicitly opted out. Requires `email`; if blank the portal
     * account creation is silently skipped.
     */
    private boolean createPortalAccount = true;
}
