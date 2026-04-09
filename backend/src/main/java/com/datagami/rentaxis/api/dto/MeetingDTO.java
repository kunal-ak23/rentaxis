package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class MeetingDTO {
    private UUID id;
    private String type;
    private String status;
    private String purpose;
    private String title;
    private String notes;
    private Instant slotStart;
    private Instant slotEnd;
    private UUID hostUserId;
    private String hostName;
    private UUID requesterUserId;
    private String requesterName;
    private UUID leaseId;
    private String leaseLabel;
    private UUID propertyId;
    private String propertyName;
    private UUID unitId;
    private String unitNumber;
    private MeetingDetailDTO details;
    private Instant createdAt;
    private Instant updatedAt;
}
