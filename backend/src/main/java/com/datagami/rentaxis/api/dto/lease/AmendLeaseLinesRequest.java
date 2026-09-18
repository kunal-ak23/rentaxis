package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Replacement lines for a posted lease, plus why (spec §6.5).
 *
 * <p>{@code reason} lands in the reversal's narration, so it is what an auditor
 * reads next to the TCR three years from now. It is not optional in spirit; it is
 * accepted as blank only because a reversal with an empty reason is still better
 * than a caller inventing a placeholder.</p>
 */
public record AmendLeaseLinesRequest(@NotEmpty(message = "At least one line is required")
                                     List<LeaseLineInput> lines,
                                     String reason) {
}
