package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record MeetingPayload(
        UUID meetingId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String purpose,
        String meetingType,
        String scheduledAtIso,
        String location,
        String status
) {}
