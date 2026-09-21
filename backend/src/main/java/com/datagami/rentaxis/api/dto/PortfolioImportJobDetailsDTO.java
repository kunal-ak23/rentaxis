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

    /**
     * Cut-over import only: contracts turned into DRAFT leases, and property role
     * mappings taken from the sheet's account columns.
     *
     * <p>{@link Integer} rather than {@code int} so {@code NON_NULL} keeps every
     * payload a v1 import writes byte-for-byte what it wrote before. New counters
     * belong on this wrapper and never as a new array element — the controller
     * discriminates the two on-disk shapes by their first character.</p>
     */
    private Integer contractsCreated;

    /** @see #contractsCreated */
    private Integer mappingsCreated;
}
