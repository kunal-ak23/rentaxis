package com.datagami.rentaxis.domain.entity.enums;

/**
 * When a lease's output VAT reaches {@code OUTPUT_VAT} (spec 2026-09-24 §1).
 *
 * <ul>
 *   <li>{@link #INSTALMENT} — the {@code TCO} parks it in {@code OUTPUT_VAT_DEFERRED}
 *       and each instalment's tax point moves its share across ({@code VTP}).</li>
 *   <li>{@link #CONTRACT} — the pre-2026-09-24 model: the {@code TCO} credits
 *       {@code OUTPUT_VAT} in full. Kept for leases already on the books, whose VAT
 *       may already be in a filed return (changeset 108 backfills them).</li>
 * </ul>
 */
public enum VatTiming {
    INSTALMENT, CONTRACT
}
