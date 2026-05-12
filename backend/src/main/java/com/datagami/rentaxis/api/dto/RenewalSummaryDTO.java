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
            List<ReminderEntry> reminders
    ) {}

    public record ReminderEntry(int slot, String status, LocalDate sentAt) {}
}
