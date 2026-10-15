package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
/**
 * {@code toEmail}, when set, is the address the mail goes to, whatever {@code
 * userId}'s row says by the time it is dispatched (re-review P2-A): a person-level
 * forgot-password link is mailed to the address it was asked for, captured when
 * it was issued — never to a row's current address, which an org admin can change
 * between issue and dispatch. The row then only supplies the language.
 */
public record PasswordResetRequestedPayload(UUID userId, String userName, String resetUrl, String expiresAtIso,
                                            String toEmail) {
    public PasswordResetRequestedPayload(UUID userId, String userName, String resetUrl, String expiresAtIso) {
        this(userId, userName, resetUrl, expiresAtIso, null);
    }
}
