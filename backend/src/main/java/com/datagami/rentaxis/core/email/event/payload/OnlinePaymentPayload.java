package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record OnlinePaymentPayload(
        UUID onlinePaymentId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String amountDisplay,
        String gatewayReference,
        String failureReason
) {}
