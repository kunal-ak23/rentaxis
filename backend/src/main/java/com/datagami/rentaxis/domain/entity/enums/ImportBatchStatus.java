package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a cut-over import stands.
 *
 * <ul>
 *   <li>{@code DRAFT} — the leases exist and nothing has been posted.</li>
 *   <li>{@code POSTED} — the batch's journals are in the ledger and the whole
 *       thing can still be undone.</li>
 *   <li>{@code REVERSED} — the journals have been taken off and every lease is
 *       back to a clean DRAFT. A reversed batch is never marked POSTED again
 *       ({@code ImportBatchService.markPosted} refuses it): re-posting the same
 *       contracts creates a successor batch to hold the new journals.</li>
 *   <li>{@code DISCARDED} — the batch and everything it created are gone. Only a
 *       DRAFT or a REVERSED batch can reach it, because a POSTED one still has
 *       journals behind its leases; it is what makes "correct the workbook and
 *       import it again" possible, since the properties, units, renters and
 *       contract references a failed import left behind are exactly what the
 *       "already exists" rules would otherwise refuse.</li>
 * </ul>
 */
public enum ImportBatchStatus { DRAFT, POSTED, REVERSED, DISCARDED }
