package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_gateways")
@Getter
@Setter
public class PaymentGateway {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active")
    private Boolean isActive;

    @Column(name = "sdk_js_url", length = 500)
    private String sdkJsUrl;

    @Column(name = "supported_currencies", length = 200)
    private String supportedCurrencies;

    @Column(name = "created_at")
    private Instant createdAt;
}
