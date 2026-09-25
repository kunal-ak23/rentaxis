package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F14-39: an assignment of a lease to another renter, with what posting it moves.
 *
 * @param balances what the outgoing renter's sub-ledger holds on this lease, account
 *                 by account (debit positive) — what the post moves to the incoming one
 * @param overdue  the outgoing renter's overdue instalments on the lease
 */
public record LeaseAssignmentDTO(UUID id, UUID leaseId, UUID fromRenterId, String fromRenterName,
                                 UUID toRenterId, String toRenterName, LocalDate effectiveDate, String reason,
                                 boolean takeOverOverdue, String status, UUID journalId, String journalNumber,
                                 Instant createdAt, Instant postedAt,
                                 List<Balance> balances, List<Overdue> overdue, int chequesMoving) {

    public record Balance(UUID accountId, String accountCode, String accountName, String accountNameAr,
                          BigDecimal amount) {
    }

    public record Overdue(UUID chequeId, int seqNo, String chequeNumber, LocalDate chequeDate, String status,
                          BigDecimal amount) {
    }
}
