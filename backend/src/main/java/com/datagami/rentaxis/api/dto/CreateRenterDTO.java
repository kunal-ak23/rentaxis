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
}
