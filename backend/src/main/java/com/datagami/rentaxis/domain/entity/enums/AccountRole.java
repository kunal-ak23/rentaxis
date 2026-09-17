package com.datagami.rentaxis.domain.entity.enums;

/** Abstract account purposes. Posting rules speak roles; AccountResolver maps (role, property) to a leaf. Spec §5.1. */
public enum AccountRole {
    RENT_RECEIVABLE, ADVANCE_RENT, RENTAL_INCOME, PDC_RECEIVABLE, BANK,
    SECURITY_DEPOSIT, ADMIN_FEE, PARKING_INCOME, PARKING_DEPOSIT, COOLING_CHARGES,
    MAINTENANCE_CHARGES, RENT_PENALTY, CHEQUE_RETURN_PENALTY, OTHER_INCOME,
    FORFEITED_INCOME, DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT,
    OPENING_BALANCE_DIFFERENCE;

    /** Roles that are normally per-property (template rows). The rest default to tenant-level mappings. */
    public boolean isPropertyScoped() {
        return switch (this) {
            case DISCOUNT_ALLOWED, ROUNDING_OFF, CASH, OUTPUT_VAT, INPUT_VAT, OPENING_BALANCE_DIFFERENCE -> false;
            default -> true;
        };
    }
}
