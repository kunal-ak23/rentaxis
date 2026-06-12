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
    /**
     * Optional value date for this status change. When set, the status-change
     * timestamp and any ledger postings are recorded on this date instead of
     * today — used when entering historical data (portfolio onboarding,
     * catch-up bookkeeping). Defaults to today when absent.
     */
    private LocalDate effectiveDate;
}
