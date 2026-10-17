package com.datagami.rentaxis.domain.entity.enums;

public enum AdditionCategory {
    PREPAID_RENT,
    UTILITY_OVERPAYMENT,
    DEPOSIT_INTEREST,
    LANDLORD_COMPENSATION,
    OTHER,
    /**
     * Follow-up ruling R1: the unearned part of a periodic fee a cut-over lease took to
     * income at posting, refunded per day; debits the fee's own income leaf. Offered on
     * the statement and never added unless the user adds it.
     */
    UNEARNED_FEE_REFUND
}
