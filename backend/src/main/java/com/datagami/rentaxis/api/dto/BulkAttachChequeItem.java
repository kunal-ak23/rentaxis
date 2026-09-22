package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One scanned cheque assigned to one row of the lease's register.
 *
 * <p>{@code chequeId} names the row. {@code scheduleId} is what the same field
 * was called while instalments were payment schedules; it is kept as a JSON alias
 * so the dashboard's existing upload screen keeps binding without a change, and
 * either spelling carries a cheque id.</p>
 */
@Data
public class BulkAttachChequeItem {

    @NotNull
    @JsonAlias("scheduleId")
    private UUID chequeId;

    @NotBlank
    private String chequeNumber;

    @NotNull
    private LocalDate chequeDate;

    @NotBlank
    private String bankName;

    private String payerName;

    @NotBlank
    private String imageUrl;

    @NotBlank
    private String imageBlobPath;

    @NotNull
    private OffsetDateTime imageUploadedAt;

    /** The register row this scan belongs to. */
    public UUID targetId() {
        return chequeId;
    }
}
