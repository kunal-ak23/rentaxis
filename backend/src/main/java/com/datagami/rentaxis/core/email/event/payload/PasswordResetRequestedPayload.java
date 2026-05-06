package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PasswordResetRequestedPayload(UUID userId, String userName, String resetUrl, String expiresAtIso) {}
