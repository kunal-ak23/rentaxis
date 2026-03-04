package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhook_logs")
@Getter
@Setter
public class WebhookLog {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "gateway_code", nullable = false, length = 50)
    private String gatewayCode;

    @Column(name = "event_type", length = 100)
    private String eventType;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "signature_header", length = 500)
    private String signatureHeader;

    @Column(name = "processed")
    private Boolean processed;

    @Column(name = "processing_result", columnDefinition = "TEXT")
    private String processingResult;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "created_at")
    private Instant createdAt;
}
