package com.datagami.rentaxis.domain.entity.enums;

public enum JournalSourceType {
    LEASE, CHEQUE, RECOGNITION, PENALTY, SETTLEMENT, VOUCHER, OPENING_BALANCE, IMPORT, MANUAL, REVERSAL, VAT_TAX_POINT, ISSUED_CHEQUE,
    BANK_STATEMENT,
    /** Spec 2026-09-24 §3: a year-end close; only YearEndCloseService writes or reverses one. */
    YEAR_END,
    /** F15-11: the correcting clearing journal for a past journal that did not balance per property. */
    INTERPROPERTY_REPAIR,
    /** F14-38: a bad-debt write-off or a recovery on one; reversed from the lease's bad-debt card. */
    BAD_DEBT
}
