package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ReminderChannel;
import com.datagami.rentaxis.domain.entity.enums.ReminderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lease_reminders", uniqueConstraints = @UniqueConstraint(name = "uniq_reminder_opp_slot_channel", columnNames = { "opportunity_id", "slot", "channel" }))
@Getter
@Setter
public class LeaseReminder extends BaseTenantEntity {

    @Id @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_id", nullable = false)
    private RenewalOpportunity opportunity;

    @Column(nullable = false)
    private short slot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReminderStatus status = ReminderStatus.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onUpdate() { this.updatedAt = Instant.now(); }
}
