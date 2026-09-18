package com.datagami.rentaxis.core.service.lease;

import java.util.UUID;

/**
 * A posted lease's lines were amended: the old TCO was reversed by a TCR and a
 * fresh TCO was posted in its place (spec §6.5).
 *
 * <p>{@code reversedJournalId} is the <em>old</em> TCO — the entry that is now
 * REVERSED — and {@code newJournalId} is the one the lease points at from here on.
 * Plan 3 listens for this to regenerate the recognition schedule, which is why
 * both ids travel: the old segments have to be unwound, not just superseded.</p>
 */
public record LeaseAmendedEvent(UUID tenantId, UUID leaseId, UUID reversedJournalId, UUID newJournalId) {
}
