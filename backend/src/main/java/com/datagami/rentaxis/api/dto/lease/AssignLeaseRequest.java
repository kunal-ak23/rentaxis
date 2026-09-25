package com.datagami.rentaxis.api.dto.lease;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F14-39: assign the lease to another renter from {@code effectiveDate}.
 *
 * @param takeOverOverdue the incoming renter takes on what the outgoing one has
 *                        overdue; without it an assignment with overdue items is refused
 */
public record AssignLeaseRequest(UUID toRenterId, LocalDate effectiveDate, String reason, Boolean takeOverOverdue) {
    public boolean takesOverOverdue() {
        return Boolean.TRUE.equals(takeOverOverdue);
    }
}
