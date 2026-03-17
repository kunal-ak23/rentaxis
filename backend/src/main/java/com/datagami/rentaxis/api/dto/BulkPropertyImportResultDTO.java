package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class BulkPropertyImportResultDTO {
    private UUID propertyId;
    private String propertyName;
    private int buildingsCreated;
    private int unitsCreated;
    private List<String> errors = new ArrayList<>();
}
