package com.datagami.rentaxis.api.dto.rating;

import com.datagami.rentaxis.domain.entity.enums.RatingGrade;

import java.time.LocalDate;

/** A snapshot month (first of month) or today's live point. */
public record RatingTrendPointDTO(LocalDate date, int score, RatingGrade grade) {}
