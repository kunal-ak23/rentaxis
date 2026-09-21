package com.datagami.rentaxis.domain.entity.enums;

/**
 * Voucher document types. Each maps 1:1 to the {@link JournalDocType} of the same
 * name, so the journal an accountant sees in the GL carries the same prefix PACT
 * used. RCP is reserved for the rent receipt path (spec §9.3, accounting v2 plan 2).
 */
public enum VoucherType {
    PISR, BPV, RCP;

    public JournalDocType toDocType() {
        return switch (this) {
            case PISR -> JournalDocType.PISR;
            case BPV -> JournalDocType.BPV;
            case RCP -> JournalDocType.RCP;
        };
    }
}
