package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record UserInvitedPayload(
        UUID inviteeUserId,
        String inviteeName,
        String setPasswordUrl
) {}
