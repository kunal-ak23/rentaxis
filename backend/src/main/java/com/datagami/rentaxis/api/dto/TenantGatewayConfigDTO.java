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
    /**
     * The bank leaf the gateway settles into (spec §9.3). Must be an active,
     * non-group ASSET account of sub-type BANK; null means "fall back to the
     * property's BANK role", which is what happens today.
     */
    private UUID settlementAccountId;
    /** Read side only, so the settings screen can label the account without a second call. */
    private String settlementAccountName;
}
