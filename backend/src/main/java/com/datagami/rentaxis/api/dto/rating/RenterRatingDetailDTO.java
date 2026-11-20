package com.datagami.rentaxis.api.dto.rating;

import com.datagami.rentaxis.domain.entity.enums.RatingGrade;

import java.util.List;
import java.util.UUID;

public record RenterRatingDetailDTO(UUID renterId, int score, RatingGrade grade, int deductionsCount, int base,
                                    List<RatingEventDTO> events, List<RatingTrendPointDTO> trend) {}
