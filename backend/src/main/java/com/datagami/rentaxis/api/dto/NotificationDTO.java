package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class NotificationDTO {
    private UUID id;
    private UUID tenantId;
    private UUID userId;
    private String type;
    private String title;
    private String message;
    private String referenceType;
    private UUID referenceId;
    private String channel;
    private Boolean isRead;
    private Instant sentAt;
    private Instant createdAt;
}
