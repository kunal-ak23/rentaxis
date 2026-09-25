package com.datagami.rentaxis.domain.entity.enums;

/**
 * Why a penalty was raised, and which income account it credits once finance
 * approves it (spec §7.3).
 *
 * <p>The role travels with the reason rather than sitting in a switch inside the
 * service: the reason is what the accountant chose, and the account it lands in
 * is not a second decision anybody is allowed to make differently.</p>
 */
public enum PenaltyReason {

    /** A returned cheque — the fine the fine-settings screen configures per failure reason. Not a supply: no VAT. */
    CHEQUE_RETURN(AccountRole.CHEQUE_RETURN_PENALTY, "Cheque return", false),

    /** Rent that arrived after its grace period, charged per day late. Compensation, not a supply: no VAT. */
    LATE_PAYMENT(AccountRole.RENT_PENALTY, "Late payment", false),

    /** Anything finance raises by hand that is none of the others. VAT only when finance ticks it. */
    OTHER(AccountRole.OTHER_INCOME, "Other", false),

    /** F14-30: a service recharged to the renter (cooling, cleaning of common areas, a key card). */
    SERVICE_RECHARGE(AccountRole.MAINTENANCE_CHARGES, "Service recharge", true),

    /** F14-30: an admin or processing fee (a cheque replacement, a NOC, a contract amendment). */
    ADMIN_FEE(AccountRole.ADMIN_FEE, "Admin fee", true),

    /** F14-30: damage or cleaning charged to the renter. */
    DAMAGE(AccountRole.OTHER_INCOME, "Damage / cleaning", true),

    /** F14-49: a vendor bill on a maintenance ticket, recharged to the renter. */
    MAINTENANCE_RECHARGE(AccountRole.MAINTENANCE_CHARGES, "Maintenance recharge", true),

    /** F14-50: the fee for an amenity or parking booking. */
    BOOKING_FEE(AccountRole.OTHER_INCOME, "Booking fee", true);

    private final AccountRole incomeRole;
    private final String label;
    private final boolean vatable;

    PenaltyReason(AccountRole incomeRole, String label, boolean vatable) {
        this.incomeRole = incomeRole;
        this.label = label;
        this.vatable = vatable;
    }

    /**
     * F14-30: whether a charge of this type is consideration for a supply, so it
     * carries 5 % VAT and a tax invoice on a VAT-registered lease. Bounce fees and
     * late-payment penalties are compensation, out of scope of VAT.
     */
    public boolean vatableByDefault() {
        return vatable;
    }

    public AccountRole incomeRole() {
        return incomeRole;
    }

    /** How the reason reads in a narration an accountant will see in the ledger. */
    public String label() {
        return label;
    }
}
