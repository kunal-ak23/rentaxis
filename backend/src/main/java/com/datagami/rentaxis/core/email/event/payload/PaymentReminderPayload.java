package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record PaymentReminderPayload(
        UUID paymentScheduleId,
        UUID leaseId,
        UUID renterUserId,
        int installmentNumber,
        String amountDisplay,
        String dueDateIso,
        int daysUntilDue
) {}
