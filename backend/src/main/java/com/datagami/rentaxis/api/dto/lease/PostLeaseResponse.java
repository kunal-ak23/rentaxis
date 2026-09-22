package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;

import java.util.List;
import java.util.UUID;

/**
 * What a successful <em>Post</em> (or <em>Amend lines</em>) produced.
 *
 * <p>The lease comes back re-read rather than as the caller's copy, because
 * posting changes its status, its posting journal and — through the unit claim —
 * the occupancy the screen renders next to it. The cheques come back because each
 * one now carries a {@code pdrJournalId} and has moved to {@code REGISTERED}: the
 * grid the user was looking at a moment ago is stale in every row.</p>
 *
 * @param tcoEntryNumber the human-facing number, e.g. {@code "TCO-26/0042"} — the
 *                       one the accountant will look for in PACT.
 */
public record PostLeaseResponse(LeaseDTO lease,
                                UUID tcoJournalId,
                                String tcoEntryNumber,
                                List<ChequeDTO> cheques) {
}
