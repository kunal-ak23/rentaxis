package com.datagami.rentaxis.api.dto.penalty;

import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A penalty finance wants raised by hand — the manual counterpart of what the
 * rule engine proposes.
 *
 * <p>Proposing posts nothing, so this carries no date: the entry date is chosen
 * at approval, which is when the charge actually exists.</p>
 *
 * @param chequeId the returned instrument this is about, where there is one.
 * @param incidentDate when the charged-for thing happened (#12): the damage, the
 *        late payment, the complaint. Defaults to today; never in the future. It
 *        is recorded, not posted — the entry date is still chosen at approval.
 */
public record ProposePenaltyRequest(@NotNull UUID leaseId,
                                    UUID chequeId,
                                    @NotNull PenaltyReason reason,
                                    @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
                                    String description,
                                    LocalDate incidentDate,
                                    /* F14-30: VAT on this charge; null = the reason's default on a VAT lease. */
                                    Boolean vatable) {

    /** The shape before #12, for callers that have no incident date to give. */
    public ProposePenaltyRequest(UUID leaseId, UUID chequeId, PenaltyReason reason, BigDecimal amount,
                                 String description) {
        this(leaseId, chequeId, reason, amount, description, null, null);
    }

    /** The shape before F14-30: VAT follows the reason and the lease. */
    public ProposePenaltyRequest(UUID leaseId, UUID chequeId, PenaltyReason reason, BigDecimal amount,
                                 String description, LocalDate incidentDate) {
        this(leaseId, chequeId, reason, amount, description, incidentDate, null);
    }
}
