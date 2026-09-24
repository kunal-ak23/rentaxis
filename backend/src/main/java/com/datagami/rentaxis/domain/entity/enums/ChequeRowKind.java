package com.datagami.rentaxis.domain.entity.enums;

/**
 * What a cheque row collects (PR #348 re-review N1). Set by whoever writes the
 * row — the generator, the portfolio import, an addendum or extension, the grid —
 * so that spreading a contract's VAT over the rows never has to guess a deposit
 * from its amount. Null on a row written before the column existed, or typed on
 * the grid with no kind; those fall back to the amount-and-narration heuristic in
 * {@code InstalmentVat}.
 */
public enum ChequeRowKind {
    /** Rent instalments only. */
    RENT,
    /** Fees only (admin fee, parking, …). */
    FEE,
    /** A refundable deposit only — never carries VAT. */
    DEPOSIT,
    /** A rent instalment with the deposit and fees folded in (the generator's cheque 1). */
    MIXED
}
