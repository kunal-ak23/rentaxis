package com.datagami.rentaxis.api.dto.rating;

import com.datagami.rentaxis.domain.entity.enums.RatingGrade;

import java.time.Instant;
import java.util.UUID;

public record RenterRatingRowDTO(UUID renterId, String renterName, String renterNameAr, int score,
                                 RatingGrade grade, int deductionsCount, UUID currentLeaseId, String unitLabel,
                                 String propertyName, Instant computedAt) {}
