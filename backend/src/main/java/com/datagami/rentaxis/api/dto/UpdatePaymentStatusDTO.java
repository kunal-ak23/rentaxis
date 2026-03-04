package com.datagami.rentaxis.api.dto;

import lombok.Data;

@Data
public class UpdatePaymentStatusDTO {
    private String chequeNumber;
    private String bankName;
    private String payerName;
    private String notes;
}
