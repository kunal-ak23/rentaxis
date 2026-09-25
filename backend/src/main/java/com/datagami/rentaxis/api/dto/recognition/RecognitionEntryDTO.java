package com.datagami.rentaxis.api.dto.recognition;

import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the lease page's <b>Recognition schedule</b> tab (spec §8.2), and of
 * the month-end close's pending list (spec §11).
 *
 * <p>{@code journalNumber} travels alongside {@code journalId} because the
 * accountant reading the tab wants "CIL-2026-0007", not a UUID, and asking the
 * ledger for it row by row would be thirteen queries for one table.</p>
 *
 * <p><b>{@code propertyId}, {@code propertyName} and {@code unitName} travel for
 * the same reason.</b> The month-end page groups what is pending <em>by
 * property</em> — an accountant closing a month works one building at a time, and
 * a flat list of thirteen leases' worth of rows is not a worklist. The entry
 * carries no property of its own; it is the lease's, through the unit, and asking
 * for it row by row would be two lazy loads per line on a page that can hold
 * hundreds. They are filled by a single batch lookup in
 * {@code RecognitionService}, and are null only for a row whose lease has no unit
 * — which the schema does not allow.</p>
 */
public record RecognitionEntryDTO(
        UUID id,
        UUID leaseId,
        UUID segmentId,
        UUID propertyId,
        String propertyName,
        String unitName,
        LocalDate periodStart,
        LocalDate periodEnd,
        int days,
        BigDecimal amount,
        RecognitionStatus status,
        UUID journalId,
        String journalNumber,
        Instant postedAt,
        /* F14-18: the periodic fee this row earns (its charge type), or null for rent. */
        String chargeCode,
        String chargeName,
        String chargeNameAr) {

    /** A rent row — the shape before F14-18. */
    public RecognitionEntryDTO(UUID id, UUID leaseId, UUID segmentId, UUID propertyId, String propertyName,
                               String unitName, LocalDate periodStart, LocalDate periodEnd, int days,
                               BigDecimal amount, RecognitionStatus status, UUID journalId, String journalNumber,
                               Instant postedAt) {
        this(id, leaseId, segmentId, propertyId, propertyName, unitName, periodStart, periodEnd, days, amount,
                status, journalId, journalNumber, postedAt, null, null, null);
    }

    /** Rent, as opposed to a periodic fee earned over the term. */
    public boolean rent() {
        return chargeCode == null;
    }
}
