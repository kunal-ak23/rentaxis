package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record LeasePayload(
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String unitLabel,
        String propertyName,
        String startDateIso,
        String endDateIso,
        String monthlyRentDisplay,
        String contractSignedUrl,
        /*
         * Break-it R2 review A N2: on LEASE_TERMINATED, the term ("dd/MM/yyyy – dd/MM/yyyy")
         * of a renewal the renter had been asked to sign and that the termination withdrew;
         * null otherwise. The email says so, because the signature link now leads nowhere.
         */
        String withdrawnRenewalTerm
) {
    /** Every lease email but a termination that withdrew a renewal. */
    public LeasePayload(UUID leaseId, UUID renterUserId, UUID propertyManagerUserId, String unitLabel,
                        String propertyName, String startDateIso, String endDateIso,
                        String monthlyRentDisplay, String contractSignedUrl) {
        this(leaseId, renterUserId, propertyManagerUserId, unitLabel, propertyName, startDateIso, endDateIso,
                monthlyRentDisplay, contractSignedUrl, null);
    }
}
