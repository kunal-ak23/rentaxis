package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateMeetingDTO {
    @NotBlank
    private String type;
    @NotBlank
    private String purpose;
    private String title;
    private String notes;
    @NotNull
    private Instant slotStart;
    @NotNull
    private UUID hostUserId;
    private UUID leaseId;
    private UUID propertyId;
    private UUID unitId;
    private UUID[] paymentScheduleIds;
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String detailNotes;
}
