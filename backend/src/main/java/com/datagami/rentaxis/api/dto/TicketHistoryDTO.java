package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class TicketHistoryDTO {
    private UUID id;
    private UUID ticketId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private UUID assignedFrom;
    private UUID assignedTo;
    private UUID performedBy;
    private String performedByName;
    private String notes;
    private Instant createdAt;
}
