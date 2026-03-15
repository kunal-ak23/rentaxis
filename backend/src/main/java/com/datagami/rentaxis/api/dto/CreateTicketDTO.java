package com.datagami.rentaxis.api.dto;

import lombok.Data;

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
}
