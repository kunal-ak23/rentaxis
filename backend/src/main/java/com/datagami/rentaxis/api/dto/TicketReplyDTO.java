package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class TicketReplyDTO {
    private UUID id;
    private UUID ticketId;
    private UUID userId;
    private String userName;
    private String message;
    private Instant createdAt;
}
