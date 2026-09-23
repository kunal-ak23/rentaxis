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
                               Instant createdAt) {
}
