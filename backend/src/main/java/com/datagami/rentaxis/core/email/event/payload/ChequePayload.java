package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record ChequePayload(
        UUID paymentScheduleId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        int installmentNumber,
        String chequeNumber,
        String bankName,
        String amountDisplay,
        String dueDateIso,
        String depositDateIso,
        String failureReason
) {}
