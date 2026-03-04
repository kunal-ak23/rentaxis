package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class TenantGatewayConfigDTO {
    private UUID id;
    private UUID gatewayId;
    private String gatewayCode;
    private String gatewayName;
    private String apiKey;
    private String apiSecret;
    private String webhookSecret;
    private String apiKeyMasked;
    private Boolean hasWebhookSecret;
    private Boolean isActive;
    private Boolean isTestMode;
}
