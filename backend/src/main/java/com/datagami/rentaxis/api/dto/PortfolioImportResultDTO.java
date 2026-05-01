package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class PortfolioImportResultDTO {
    private UUID jobId;
    private String status;
    private int propertiesCreated;
    private int buildingsCreated;
    private int unitsCreated;
    private int rentersCreated;
    private int leasesCreated;
    private int paymentSchedulesCreated;
    private List<ImportErrorDTO> errors;
    private List<ImportErrorDTO> warnings;
}
