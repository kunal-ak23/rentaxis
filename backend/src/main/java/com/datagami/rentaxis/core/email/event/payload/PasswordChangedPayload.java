package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
/** {@code toEmail}: as on {@link PasswordResetRequestedPayload} — a fixed address, when set. */
public record PasswordChangedPayload(UUID userId, String userName, String changedAtIso, String ipAddress,
                                     String toEmail) {
    public PasswordChangedPayload(UUID userId, String userName, String changedAtIso, String ipAddress) {
        this(userId, userName, changedAtIso, ipAddress, null);
    }
}
