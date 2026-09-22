package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class MeetingDetailDTO {
    private String detailType;

    /** The register rows a CHEQUE_REPLACEMENT meeting is about. */
    private UUID[] chequeIds;

    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String notes;

    /**
     * The same array under the key it had while instalments were schedules.
     *
     * <p>Emitted so the dashboard's meeting screen keeps rendering its "N cheques"
     * badge until it moves to the new name; the ids it carries were always cheque
     * ids in intent, and are now literally so.</p>
     */
    @JsonProperty("paymentScheduleIds")
    public UUID[] getLegacyScheduleIds() {
        return chequeIds;
    }
}
