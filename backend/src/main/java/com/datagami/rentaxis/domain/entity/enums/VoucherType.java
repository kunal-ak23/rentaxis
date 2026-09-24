package com.datagami.rentaxis.domain.entity.enums;

/**
 * Voucher document types. Each maps 1:1 to the {@link JournalDocType} of the same
 * name, so the journal an accountant sees in the GL carries the same prefix PACT
 * used. RCP is reserved for the rent receipt path (spec §9.3, accounting v2 plan 2).
 */
public enum VoucherType {
    PISR, BPV, RCP,
    /** F14-40: a supplier's credit note (our debit note): reduces the vendor's open invoices and reverses input VAT. */
    PCN;

    public JournalDocType toDocType() {
        return switch (this) {
            case PISR -> JournalDocType.PISR;
            case BPV -> JournalDocType.BPV;
            case RCP -> JournalDocType.RCP;
            case PCN -> JournalDocType.PCN;
        };
    }
}
