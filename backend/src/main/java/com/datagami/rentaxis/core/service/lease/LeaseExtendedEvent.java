package com.datagami.rentaxis.core.service.lease;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A posted lease's term was extended: new lines appended, a further {@code TCO}
 * and its {@code PDR}s written, {@code end_date} moved (spec §6.7).
 *
 * <p>Both dates travel because the extension is a <em>window</em>, not a new end:
 * plan 3 builds a rent segment for {@code previousEndDate + 1 … newEndDate} and
 * has to leave the original term's segments exactly as they are. {@code lineIds}
 * names the lines that segment is cut from, so a listener never has to guess which
 * of the lease's lines are new — after the second extension there is no ordering
 * that would tell it.</p>
 *
 * <p>Published inside the extension's transaction, like {@link LeasePostedEvent},
 * so a {@code @TransactionalEventListener} only hears about extensions that
 * committed.</p>
 */
public record LeaseExtendedEvent(UUID tenantId,
                                 UUID leaseId,
                                 LocalDate previousEndDate,
                                 LocalDate newEndDate,
                                 List<UUID> lineIds) {
}
