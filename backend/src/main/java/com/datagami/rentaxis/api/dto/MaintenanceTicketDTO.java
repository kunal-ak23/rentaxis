package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class MaintenanceTicketDTO {
    private UUID id;
    /** "TKT-yy/n" (#20); null only on a row written outside the service. */
    private String reference;
    private UUID tenantId;
    private UUID propertyId;
    private UUID unitId;
    private UUID leaseId;
    private UUID reportedBy;
    private UUID assignedTo;
    private String title;
    private String description;
    private String category;
    private String priority;
    private String status;
    private Integer estimatedResolutionHours;
    private Instant resolvedAt;
    private Instant closedAt;
    private String closureOtp;
    private Integer satisfactionRating;
    private String satisfactionComment;
    private String onBehalfOf;
    private java.time.LocalDate reportedDate;
    private Instant createdAt;
    private Instant updatedAt;

    // Enriched fields
    private String reporterName;
    private String assigneeName;
    private String propertyName;
    private String unitNumber;
    private long replyCount;
    private long attachmentCount;
}
