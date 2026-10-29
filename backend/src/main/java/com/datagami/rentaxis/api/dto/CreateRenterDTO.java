package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.core.util.PhoneNumbers;
import com.datagami.rentaxis.domain.entity.enums.Language;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateRenterDTO {
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

    private Language primaryLanguage = Language.EN;

    /**
     * Whether to give this renter portal access (a new invited User, or an
     * existing unlinked RENTER user of the organisation). Defaults to true.
     *
     * <p>Owner ruling 2026-09-29: every Tenant with an email gets portal access,
     * and the web form no longer offers an opt-out. The field is kept for API
     * back-compat and for internal callers that already hold the person's
     * account elsewhere (the marketplace enquiry conversion in
     * {@code ListingLeaseService}); {@code false} is still honoured there.</p>
     */
    private boolean createPortalAccount = true;

    /**
     * As {@code UpdateProfileRequest} does: Unicode spaces (a number pasted from iOS or
     * WhatsApp) and Arabic-Indic digits are normalized before {@code @Pattern} sees them,
     * so a number the web accepted is not refused here.
     */
    public void setPhone(String phone) {
        this.phone = PhoneNumbers.normalizeUnicode(phone);
    }
}
