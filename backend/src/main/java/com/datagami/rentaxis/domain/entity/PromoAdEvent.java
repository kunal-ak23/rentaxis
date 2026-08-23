package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One impression or click. {@code day} is the Asia/Dubai calendar day, set by
 * the service rather than derived in SQL — the database's timezone is not the
 * product's. A partial unique index on (ad_id, renter_user_id, day) collapses
 * repeat impressions within a day; clicks are excluded from it.
 */
@Entity
@Table(name = "promo_ad_events")
@Getter
@Setter
public class PromoAdEvent extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "ad_id", nullable = false)
    private UUID adId;

    @Column(name = "renter_user_id", nullable = false)
    private UUID renterUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 12)
    private PromoEventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(nullable = false)
    private LocalDate day;
}
