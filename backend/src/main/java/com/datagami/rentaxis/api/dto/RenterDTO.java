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

    /**
     * What happened to portal access when this Tenant was created. Set on the
     * create response only; null on every read.
     */
    private PortalAccount portalAccount;

    public enum PortalAccount {
        /** A new portal user was created and emailed a set-password invite. */
        INVITED,
        /** The email was an unlinked portal (RENTER) user of this organisation: linked, no new invite. */
        LINKED_EXISTING,
        /**
         * The email belongs to another user of this organisation (staff, or a
         * portal user already linked to another Tenant): saved without portal access.
         */
        SKIPPED_EMAIL_IN_USE,
        /**
         * The email is this organisation's portal (RENTER) user, unlinked, but
         * deactivated: not linked, since it could not sign in. Reactivate it first.
         */
        SKIPPED_ACCOUNT_INACTIVE,
        /** No email was given, and login needs one. */
        NO_EMAIL,
        /** An internal caller asked for no portal account (createPortalAccount=false). */
        NOT_REQUESTED
    }
}
