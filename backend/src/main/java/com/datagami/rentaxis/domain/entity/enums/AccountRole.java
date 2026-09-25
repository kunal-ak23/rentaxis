package com.datagami.rentaxis.domain.entity.enums;

/** Abstract account purposes. Posting rules speak roles; AccountResolver maps (role, property) to a leaf. Spec §5.1. */
public enum AccountRole {
    RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME, PDC_RECEIVABLE, BANK,
    SECURITY_DEPOSIT, ADMIN_FEE, PARKING_INCOME, PARKING_DEPOSIT, COOLING_CHARGES,
    MAINTENANCE_CHARGES, RENT_PENALTY, CHEQUE_RETURN_PENALTY, OTHER_INCOME,
    FORFEITED_INCOME, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT,
    OPENING_BALANCE_DIFFERENCE,
    /** "Output VAT – not yet due" (spec 2026-09-24 §1): VAT charged on a contract whose instalment has not reached its tax point. */
    OUTPUT_VAT_DEFERRED,
    /** Finance-ops spec §2: post-dated cheques we issued and the bank has not paid yet (B-02-001). */
    PDC_PAYABLE,
    /** Finance-ops spec §3: bank charges booked from a statement line (D-02-003). */
    BANK_CHARGES,
    /** Finance-ops spec §3: interest the bank credits (C-02-001). */
    BANK_INTEREST_INCOME,
    /** Finance-ops spec §3: receipts on a statement nobody has identified yet (B-01-06). */
    BANK_SUSPENSE,
    /** F14-36: deposit refunds a finalized settlement owes renters, until a payment voucher pays them (B-01-07). */
    RENTER_REFUND_PAYABLE,
    /** F14-18: periodic fees (parking, cooling, service charge) billed for the term and not yet earned (B-01-08). */
    UNEARNED_CHARGES,
    /** Spec 2026-09-24 §3: the year-end close's equity leaf (F-03); lines carry the property dimension. */
    RETAINED_EARNINGS,
    /**
     * F15-11: inter-property clearing (A-02-06), one leaf per property. A journal whose
     * lines span properties posts a clearing leg in each, so every property's trial
     * balance nets to zero and the clearing leaves net to zero company-wide.
     */
    INTERPROPERTY_CLEARING;

    /** Roles that are normally per-property (template rows). The rest default to tenant-level mappings. */
    public boolean isPropertyScoped() {
        return switch (this) {
            case DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE,
                 OUTPUT_VAT_DEFERRED, PDC_PAYABLE,
                 BANK_CHARGES, BANK_INTEREST_INCOME, BANK_SUSPENSE, RENTER_REFUND_PAYABLE, UNEARNED_CHARGES, RETAINED_EARNINGS -> false;
            default -> true;
        };
    }
}
