package com.datagami.rentaxis.domain.entity.enums;

public enum AccountSubType {
    // ASSET sub-types
    FIXED_ASSET,
    BANK,
    CASH,
    RECEIVABLE,
    PDC_RECEIVABLE,
    OTHER_ASSET,

    // LIABILITY sub-types
    PAYABLE,
    ADVANCE,
    DEPOSIT_HELD,
    PDC_PAYABLE,
    OTHER_LIABILITY,

    // INCOME sub-types
    RENTAL_INCOME,
    OTHER_INCOME,

    // EXPENSE sub-types
    DIRECT_EXPENSE,
    INDIRECT_EXPENSE,
    SALARY_EXPENSE,
    OTHER_EXPENSE,

    // EQUITY sub-types
    CAPITAL,
    RETAINED_EARNINGS
}
