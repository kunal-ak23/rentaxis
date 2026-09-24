package com.datagami.rentaxis.domain.entity.enums;

/** What a VAT tax point is for (spec 2026-09-24 §1). */
public enum VatTaxPointKind {
    /** One instalment's tax point: min(cheque date, receipt date). */
    INSTALMENT,
    /** The settling pair a termination posts on its TCR; negative when it credits VAT back. */
    TERMINATION_ADJUSTMENT
}
