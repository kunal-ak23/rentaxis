package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper persisted into {@code import_jobs.errors} (JSONB) for jobs that need to
 * carry warnings or new bulk-import counters alongside any validation errors.
 *
 * <p>The legacy on-disk format is a raw {@code List<ImportErrorDTO>} (a JSON array).
 * To stay backward-compatible without a DB migration, this wrapper is serialized
 * as a JSON object — the controller picks the object form for new jobs and falls
 * back to the array form for old ones.</p>
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortfolioImportJobDetailsDTO {
    private List<ImportErrorDTO> errors = new ArrayList<>();
    private List<ImportErrorDTO> warnings = new ArrayList<>();
    private Integer chequesFromSheet;
    private Integer bookingDepositsCreated;
}
