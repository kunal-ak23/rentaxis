package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class TicketAttachmentDTO {
    private UUID id;
    private UUID ticketId;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Instant uploadedAt;
}
