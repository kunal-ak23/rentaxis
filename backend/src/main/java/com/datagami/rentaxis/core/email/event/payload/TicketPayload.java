package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record TicketPayload(
        UUID ticketId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String title,
        String category,
        String priority,
        String status,
        String latestReply
) {}
