package com.datagami.rentaxis.domain.entity.enums;

/** What a VAT tax point is for (spec 2026-09-24 §1). */
public enum VatTaxPointKind {
    /** One instalment's tax point: min(cheque date, receipt date). */
    INSTALMENT,
    /** F14-37: VAT on the recharges (damage, cleaning, keys) deducted at a settlement; posted on the STL. */
    SETTLEMENT,
    /** The settling pair a termination posts on its TCR; negative when it credits VAT back. */
    TERMINATION_ADJUSTMENT,
    /**
     * F14-11: a CONTRACT-timing lease declares its VAT on the contract date, so the
     * posting itself is the tax point: POSTED with the TCO, and its tax invoice is
     * issued in the same transaction. An amendment records the delta the same way.
     */
    CONTRACT,
    /** F14-32: VAT credited back by a credit addendum (mid-term reduction) beyond what was still to be declared. */
    REDUCTION
}
