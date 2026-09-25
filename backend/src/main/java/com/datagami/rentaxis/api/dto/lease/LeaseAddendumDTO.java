package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An addendum as the lease page shows it: its number, what it charged, and the
 * {@code TCO} that raised it.
 *
 * <p>{@code ejariPending} is derived rather than stored — a blank Ejari number
 * <em>is</em> "pending", and a second column saying so would be a second place
 * for one fact.</p>
 *
 * <p>{@code superseded} is derived too: {@code LeasePostingService.amendLines}
 * reverses every POSTED {@code TCO} against the lease, including an addendum's
 * own, when it rebuilds the ledger from a fresh set of lines. The addendum row
 * itself is never touched by an amend — it still names the TCO that raised it —
 * so without this flag the lease page kept showing a reversed entry number as
 * if it were still live.</p>
 */
public record LeaseAddendumDTO(UUID id,
                               String addendumNumber,
                               LocalDate effectiveFrom,
                               LocalDate contractDate,
                               String ejariNumber,
                               boolean ejariPending,
                               String reason,
                               BigDecimal value,
                               UUID tcoJournalId,
                               String tcoEntryNumber,
                               boolean superseded,
                               Instant createdAt,
                               /* F14-32: CHARGE or CREDIT; for a credit, where the excess went and the lines it cut. */
                               String kind,
                               String excess,
                               java.util.List<LeaseAddendumCreditDTO> credits) {

    public LeaseAddendumDTO(UUID id, String addendumNumber, LocalDate effectiveFrom, LocalDate contractDate,
                            String ejariNumber, boolean ejariPending, String reason, BigDecimal value,
                            UUID tcoJournalId, String tcoEntryNumber, boolean superseded, Instant createdAt) {
        this(id, addendumNumber, effectiveFrom, contractDate, ejariNumber, ejariPending, reason, value,
                tcoJournalId, tcoEntryNumber, superseded, createdAt, "CHARGE", null, java.util.List.of());
    }
}
