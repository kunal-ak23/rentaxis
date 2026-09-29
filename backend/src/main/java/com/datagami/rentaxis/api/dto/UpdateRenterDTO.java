package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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

    @Email
    private String email;

    private String phone;

    /** Null keeps the renter's current language. */
    private Language primaryLanguage;
}
