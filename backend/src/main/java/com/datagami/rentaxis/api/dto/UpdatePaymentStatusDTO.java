package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdatePaymentStatusDTO {
    private String chequeNumber;
    private String bankName;
    private String payerName;
    private LocalDate chequeDate;
    private String notes;
}
