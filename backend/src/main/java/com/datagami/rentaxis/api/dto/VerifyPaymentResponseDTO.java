package com.datagami.rentaxis.api.dto;

import lombok.Data;

@Data
public class VerifyPaymentResponseDTO {
    private boolean success;
    private String message;
    private String paymentId;
}
