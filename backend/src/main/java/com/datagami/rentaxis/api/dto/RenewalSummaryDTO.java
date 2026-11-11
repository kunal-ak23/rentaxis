package com.datagami.rentaxis.api.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record RenewalSummaryDTO(List<LeaseRenewalView> leases) {

    public record LeaseRenewalView(
            UUID leaseId,
            String unitNumber,
            String propertyNameEn,
            LocalDate endDate,
            long daysRemaining,
            UUID opportunityId,
            String stage,
            String intent,
            List<ReminderEntry> reminders,
            // Break-it R3 portal3 F8: the contract is over (past its end date, or
            // terminated/expired/closed) — shown as "ended N days ago", no renewal choice.
            boolean ended,
            long endedDaysAgo,
            // Decree 43: "WITHIN" or "ABOVE" the RERA cap when a renewal has been drafted
            // for this contract and a DLD index range matches it; null otherwise. Read-only.
            String reraCap
    ) {}

    public record ReminderEntry(int slot, String status, LocalDate sentAt) {}
}
