package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class UpdatePaymentStatusDTO {
    private String chequeNumber;
    private String bankName;
    private String payerName;
    private LocalDate chequeDate;
    private String chequeImageUrl;
    private String chequeImageBlobPath;
    private OffsetDateTime chequeImageUploadedAt;
    private String notes;
}
