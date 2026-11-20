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

import java.time.LocalDate;
import java.util.UUID;

/** Month-end rating of a renter, kept for the trend chart. */
@Entity
@Table(name = "renter_rating_snapshots")
@Getter
@Setter
public class RenterRatingSnapshot extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "renter_id", nullable = false) private UUID renterId;
    /** First day of the snapshot month. */
    @Column(nullable = false) private LocalDate month;
    @Column(nullable = false) private int score;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8) private RatingGrade grade;
}
