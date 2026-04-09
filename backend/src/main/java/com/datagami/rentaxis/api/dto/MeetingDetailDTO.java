package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class MeetingDetailDTO {
    private String detailType;
    private UUID[] paymentScheduleIds;
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String notes;
}
