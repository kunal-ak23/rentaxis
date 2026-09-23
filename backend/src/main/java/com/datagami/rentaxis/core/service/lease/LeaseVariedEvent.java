package com.datagami.rentaxis.core.service.lease;

import java.util.List;
import java.util.UUID;

/**
 * A posted lease took an addendum: lines appended, a further {@code TCO} and its
 * {@code PDR}s written, the end date unchanged. {@code lineIds} names the new
 * lines so recognition cuts a segment for each RENT one over its own window.
 * Published inside the transaction, like {@link LeaseExtendedEvent}.
 */
public record LeaseVariedEvent(UUID tenantId, UUID leaseId, UUID addendumId, List<UUID> lineIds) {
}
