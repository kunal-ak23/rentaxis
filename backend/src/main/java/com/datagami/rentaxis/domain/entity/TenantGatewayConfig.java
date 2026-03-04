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

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "is_test_mode")
    private Boolean isTestMode;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
