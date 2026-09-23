package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.Language;
import lombok.Data;

import java.util.UUID;

@Data
public class RenterDTO {
    private UUID id;
    private String nameEn;
    private String nameAr;
    private String email;
    private String phone;
    private Language primaryLanguage;
    private UUID userId;
    /**
     * True while the renter's portal invite is unused (#7). The API never returns
     * a password: onboarding is the emailed set-password link only.
     */
    private boolean invitePending;
    private java.time.Instant inviteExpiresAt;
}
