package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
public class NotificationDTO {
    private UUID id;
    private UUID tenantId;
    private UUID userId;
    private String type;
    private String title;
    private String message;
    /** Key of the structured sentence (#81); null on older rows, which read title/message. */
    private String messageKey;
    /** Raw values for {@link #messageKey}: plain decimals, ISO dates and instants. */
    private Map<String, String> params;
    private String referenceType;
    private UUID referenceId;
    private String channel;
    private Boolean isRead;
    private Instant sentAt;
    private Instant createdAt;
}
