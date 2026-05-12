package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class BulkAttachChequeItem {
    @NotNull
    private UUID scheduleId;

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
}
