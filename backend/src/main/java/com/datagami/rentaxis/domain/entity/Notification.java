package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
@AttributeOverride(name = "tenantId", column = @Column(name = "tenant_id", nullable = true))
public class Notification extends BaseTenantEntity {

    // Override to allow null tenant for system-wide notifications
    @PrePersist
    @Override
    public void onPrePersist() {
        // Don't auto-set tenant — it's explicitly set (or null for system alerts)
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String message;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(length = 20)
    private String channel = "IN_APP";

    @Column(name = "is_read")
    private Boolean isRead = false;

    @Column(name = "sent_at")
    private Instant sentAt = Instant.now();

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
