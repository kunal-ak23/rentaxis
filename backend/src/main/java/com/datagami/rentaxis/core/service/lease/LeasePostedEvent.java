package com.datagami.rentaxis.core.service.lease;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A lease has been posted: its TCO and every PDR are written and it is ACTIVE.
 *
 * <p>Published inside the posting transaction, so a listener registered with
 * {@code @TransactionalEventListener} only hears about leases that actually
 * committed. Plan 3 listens for this to build the lease's rent segments and its
 * recognition schedule (spec §8) — work that has no business happening inside the
 * posting call itself, because a failure to schedule recognition must not stop a
 * contract going on the books.</p>
 *
 * <p>Carries ids and the contract date rather than the {@code Lease}: a detached
 * entity handed to a listener that runs after commit is a lazy-loading trap.</p>
 */
public record LeasePostedEvent(UUID tenantId, UUID leaseId, LocalDate contractDate) {
}
