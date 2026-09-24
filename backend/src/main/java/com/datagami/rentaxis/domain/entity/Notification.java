package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
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

    /**
     * The sentence this row says, for rendering it in the reader's language
     * ({@code Notifications.messages.<key>} on the web). Null on rows written
     * before #81, which the reader shows as {@link #title}/{@link #message}.
     */
    @Column(name = "message_key", length = 60)
    private String messageKey;

    /** The raw values that go into {@link #messageKey}'s sentence. */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> params;

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
