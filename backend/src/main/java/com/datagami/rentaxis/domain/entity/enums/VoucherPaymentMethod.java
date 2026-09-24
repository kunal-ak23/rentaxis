package com.datagami.rentaxis.domain.entity.enums;

/**
 * How a Bank/Cash Payment Voucher paid (finance-ops spec §2, S14). CASH needs a
 * cash leaf as the payment account; TRANSFER and CHEQUE need a bank leaf.
 * Separate from {@link PaymentMethod}, which is the lease/deposit side's set.
 */
public enum VoucherPaymentMethod {
    TRANSFER, CHEQUE, CASH
}
