package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_gateway_configs")
@Getter
@Setter
public class TenantGatewayConfig extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gateway_id", nullable = false)
    private PaymentGateway gateway;

    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKeyEncrypted;

    @Column(name = "api_secret_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiSecretEncrypted;

    @Column(name = "webhook_secret_encrypted", columnDefinition = "TEXT")
    private String webhookSecretEncrypted;

    /**
     * The bank leaf the gateway actually pays out to (spec §9.3).
     *
     * <p>A capture is money in a specific bank account, not in "the property's
     * BANK role": Razorpay settles into one nominated account for the whole
     * organisation, and a {@code CRT} that debited whichever leaf the property
     * template happened to name would put the gateway's settlements somewhere the
     * bank statement never shows them. Left null, the capture falls back to the
     * BANK role exactly as a counter receipt does.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_account_id")
    private Account settlementAccount;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "is_test_mode")
    private Boolean isTestMode;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
