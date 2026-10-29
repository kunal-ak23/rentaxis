package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Body of {@code PUT /renters/{id}} (edit tenant).
 *
 * <p>Its own type rather than {@link CreateRenterDTO}: {@code createPortalAccount}
 * means nothing on an edit (a portal login is made on create, or by an invite), and
 * create's {@code primaryLanguage = EN} default would reset an Arabic-speaking
 * renter to English whenever a client left the field out. Here an omitted language
 * keeps the current one. A client that still sends {@code createPortalAccount} is
 * unaffected: Spring Boot leaves Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES} off.
 */
@Data
public class UpdateRenterDTO {
    @NotBlank
    private String nameEn;

    private String nameAr;

    /** Client request 2026-10-29: every tenant has an email and a mobile. */
    @NotBlank(message = "Email is required")
    @Email(message = "Enter a valid email address")
    private String email;

    @NotBlank(message = "Mobile is required")
    @Pattern(regexp = PhoneNumbers.LENIENT_PATTERN, message = PhoneNumbers.LENIENT_MESSAGE)
    private String phone;

    /** Null keeps the renter's current language. */
    private Language primaryLanguage;

    /**
     * As {@code UpdateProfileRequest} does: Unicode spaces (a number pasted from iOS or
     * WhatsApp) and Arabic-Indic digits are normalized before {@code @Pattern} sees them,
     * so a number the web accepted is not refused here.
     */
    public void setPhone(String phone) {
        this.phone = PhoneNumbers.normalizeUnicode(phone);
    }
}
