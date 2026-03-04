package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class PaymentGatewayDTO {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private Boolean isActive;
    private String sdkJsUrl;
    private String supportedCurrencies;
}
