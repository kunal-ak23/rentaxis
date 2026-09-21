package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a cut-over import stands. {@code DRAFT} leases exist but nothing has been
 * posted; {@code POSTED} means the batch's journals are in the ledger and the
 * whole thing can still be undone; {@code REVERSED} is the end of the line — a
 * reversed batch is history, and a corrected spreadsheet is imported as a new one.
 */
public enum ImportBatchStatus { DRAFT, POSTED, REVERSED }
