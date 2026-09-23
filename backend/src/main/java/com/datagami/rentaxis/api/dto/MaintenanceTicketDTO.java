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
    /**
     * Staff only (false for everyone else): {@code PUT /tickets/{id}/status}
     * with CLOSED would succeed for this caller. For a RESOLVED ticket that
     * means no renter can confirm with a code, the tenant does not require
     * one, or OTP closure is locked and the caller is an admin.
     */
    private boolean closableWithoutOtp;
    /** Staff only: OTP closure is permanently locked after too many wrong OTPs. */
    private boolean otpLocked;
    private Integer satisfactionRating;
    private String satisfactionComment;
    private String onBehalfOf;
    /** The renter the ticket was logged for (#19); null on legacy free-text rows. */
    private UUID onBehalfOfRenterId;
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
