package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.RatingGrade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Stored current rating of a renter (one row per renter), recomputed from lease/cheque history. */
@Entity
@Table(name = "renter_ratings")
@Getter
@Setter
public class RenterRating extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "renter_id", nullable = false) private UUID renterId;
    @Column(nullable = false) private int score;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8) private RatingGrade grade;
    @Column(name = "deductions_count", nullable = false) private int deductionsCount;
    @Column(name = "events_json", nullable = false, columnDefinition = "text") private String eventsJson;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(name = "computed_at", nullable = false) private Instant computedAt;
}
