package com.datagami.rentaxis.api.dto;

import lombok.Data;

@Data
public class CreateOrderResponseDTO {
    private String orderId;
    private long amount;
    private String currency;
    private String gatewayKey;
    private String gatewayCode;
    private String sdkJsUrl;
    private String renterName;
    private String renterEmail;
}
