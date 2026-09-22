package com.datagami.rentaxis.api.dto.penalty;

import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A penalty finance wants raised by hand — the manual counterpart of what the
 * rule engine proposes.
 *
 * <p>Proposing posts nothing, so this carries no date: the entry date is chosen
 * at approval, which is when the charge actually exists.</p>
 *
 * @param chequeId the returned instrument this is about, where there is one.
 */
public record ProposePenaltyRequest(@NotNull UUID leaseId,
                                    UUID chequeId,
                                    @NotNull PenaltyReason reason,
                                    @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
                                    String description) {
}
