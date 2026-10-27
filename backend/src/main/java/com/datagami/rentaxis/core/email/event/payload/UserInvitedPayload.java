package com.datagami.rentaxis.core.email.event.payload;

import java.util.UUID;

public record UserInvitedPayload(
        UUID inviteeUserId,
        String inviteeName,
        String setPasswordUrl,
        String inviteToken,
        /** The invitee's role name, so a promoter's invite can say what they were invited for. Null on older rows. */
        String inviteeRole
) {}
