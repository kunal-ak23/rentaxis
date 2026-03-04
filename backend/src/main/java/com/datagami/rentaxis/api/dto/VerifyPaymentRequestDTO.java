package com.datagami.rentaxis.api.dto;

import lombok.Data;

@Data
public class VerifyPaymentRequestDTO {
    private String gatewayOrderId;
    private String gatewayPaymentId;
    private String gatewaySignature;
}
