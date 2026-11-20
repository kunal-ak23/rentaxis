package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A manual, audited score adjustment (-30..+30) on a renter's rating; removal is soft. */
@Entity
@Table(name = "renter_rating_adjustments")
@Getter
@Setter
public class RenterRatingAdjustment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "renter_id", nullable = false) private UUID renterId;
    @Column(nullable = false) private int points;
    @Column(nullable = false, length = 500) private String reason;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "removed_by") private UUID removedBy;
    @Column(name = "removed_at") private Instant removedAt;
}
