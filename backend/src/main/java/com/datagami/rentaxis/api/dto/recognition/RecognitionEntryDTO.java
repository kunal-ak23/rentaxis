package com.datagami.rentaxis.api.dto.recognition;

import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the lease page's <b>Recognition schedule</b> tab (spec §8.2).
 *
 * <p>{@code journalNumber} travels alongside {@code journalId} because the
 * accountant reading the tab wants "CIL-2026-0007", not a UUID, and asking the
 * ledger for it row by row would be thirteen queries for one table.</p>
 */
public record RecognitionEntryDTO(
        UUID id,
        UUID leaseId,
        UUID segmentId,
        LocalDate periodStart,
        LocalDate periodEnd,
        int days,
        BigDecimal amount,
        RecognitionStatus status,
        UUID journalId,
        String journalNumber,
        Instant postedAt) {
}
