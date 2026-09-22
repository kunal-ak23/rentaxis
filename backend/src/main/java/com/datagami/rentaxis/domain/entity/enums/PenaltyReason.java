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

    /** A returned cheque — the fine the fine-settings screen configures per failure reason. */
    CHEQUE_RETURN(AccountRole.CHEQUE_RETURN_PENALTY, "Cheque return"),

    /** Rent that arrived after its grace period, charged per day late. */
    LATE_PAYMENT(AccountRole.RENT_PENALTY, "Late payment"),

    /** Anything finance raises by hand that is neither of the above. */
    OTHER(AccountRole.OTHER_INCOME, "Other");

    private final AccountRole incomeRole;
    private final String label;

    PenaltyReason(AccountRole incomeRole, String label) {
        this.incomeRole = incomeRole;
        this.label = label;
    }

    /** The credit side of the {@code PEN} entry, resolved against the lease's property. */
    public AccountRole incomeRole() {
        return incomeRole;
    }

    /** How the reason reads in a narration an accountant will see in the ledger. */
    public String label() {
        return label;
    }
}
