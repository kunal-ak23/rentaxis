package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.domain.entity.enums.RatingGrade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Engine output. events are newest first. */
public record RenterRatingResult(UUID renterId, int score, RatingGrade grade, int deductionsCount,
                                 List<RatingEvent> events) {

    /** SHA-256 hex of score|grade|each event's type,date,appliedPoints,sourceId. */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append(score).append('|').append(grade);
        for (RatingEvent e : events) {
            sb.append('|').append(e.type()).append(',').append(e.date()).append(',')
                    .append(e.appliedPoints()).append(',').append(e.sourceId());
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
