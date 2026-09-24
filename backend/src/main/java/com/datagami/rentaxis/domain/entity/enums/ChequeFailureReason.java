package com.datagami.rentaxis.domain.entity.enums;

/**
 * Why the bank returned a cheque. F14-22 adds the UAE return codes that were
 * recorded as a plain BOUNCE: the drawer stopped the payment, and a technical
 * return (stale, post-dated, words and figures differ). Both carry the
 * organisation's generic bounce fee and count towards the bounce threshold.
 */
public enum ChequeFailureReason {
    BOUNCE,
    SIGNATURE_MISMATCH,
    ACCOUNT_CLOSED,
    STOPPED_PAYMENT,
    TECHNICAL_RETURN;
}
