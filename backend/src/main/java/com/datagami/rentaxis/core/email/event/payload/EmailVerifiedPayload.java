package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record EmailVerifiedPayload(UUID userId, String userName, String dashboardUrl) {}
