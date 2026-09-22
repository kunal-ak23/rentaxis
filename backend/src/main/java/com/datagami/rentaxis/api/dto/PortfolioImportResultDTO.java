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
    private int chequesCreated;
    private int chequesFromSheet;
    private int bookingDepositsCreated;

    /**
     * Cut-over import only: the DRAFT batch the job produced, and what it wrote
     * into it.
     *
     * <p>{@code importBatchId} is how the web goes from "my upload finished" to the
     * batch screen that can post or reverse it, without having to guess which batch
     * is its own. Null for a v1 portfolio import and for a cut-over job that has not
     * reached the persist phase.</p>
     */
    private UUID importBatchId;
    private int contractsCreated;
    private int mappingsCreated;
    private List<ImportErrorDTO> errors;
    private List<ImportErrorDTO> warnings;
}
