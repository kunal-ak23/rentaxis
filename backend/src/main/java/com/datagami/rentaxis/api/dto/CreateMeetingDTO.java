package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.MeetingType;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateMeetingDTO {
    @NotNull
    private MeetingType type;
    @NotNull
    private MeetingPurpose purpose;
    private String title;
    private String notes;
    @NotNull
    private Instant slotStart;
    @NotNull
    private UUID hostUserId;
    private UUID leaseId;
    private UUID propertyId;
    private UUID unitId;
    @com.fasterxml.jackson.annotation.JsonAlias("paymentScheduleIds")
    private UUID[] chequeIds;
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String detailNotes;
}
