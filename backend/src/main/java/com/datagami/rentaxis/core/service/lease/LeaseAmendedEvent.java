package com.datagami.rentaxis.core.service.lease;

import java.util.List;
import java.util.UUID;

/**
 * A posted lease's lines were amended: every POSTED {@code TCO} on it was
 * reversed by a TCR and a single fresh TCO was posted in their place (spec §6.5).
 *
 * <p>{@code reversedJournalIds} is <em>all</em> of them, oldest first, not just
 * the one {@code lease.postingJournalId} named. An extended lease carries a TCO
 * per extension alongside the contract's own, and an amendment reposts the whole
 * line set under one entry — so it has to reverse the whole set, and a consumer
 * told about only the first would believe the extension's charges were still
 * live. {@code newJournalId} is the one the lease points at from here on.</p>
 *
 * <p>Plan 3 listens for this to regenerate the recognition schedule, which is
 * why the reversed ids travel at all: the old segments have to be unwound, not
 * merely superseded.</p>
 */
public record LeaseAmendedEvent(UUID tenantId, UUID leaseId, List<UUID> reversedJournalIds, UUID newJournalId) {

    public LeaseAmendedEvent {
        reversedJournalIds = reversedJournalIds == null ? List.of() : List.copyOf(reversedJournalIds);
    }
}
