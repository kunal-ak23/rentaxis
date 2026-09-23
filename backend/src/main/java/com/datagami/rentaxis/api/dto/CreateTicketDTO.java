package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateTicketDTO {
    private UUID propertyId;
    private UUID unitId;
    private UUID leaseId;
    private String title;
    private String description;
    private String category;
    private String priority;
    private String onBehalfOf;
    /**
     * #19: the renter a staff member is logging this for, picked from the org's
     * renters. Resolved in the caller's tenant; when set, it wins over the
     * free-text {@code onBehalfOf}, which is filled with the renter's name.
     */
    private UUID onBehalfOfRenterId;

    /** When the tenant reported it; defaults to today if the caller omits it. */
    private LocalDate reportedDate;
}
