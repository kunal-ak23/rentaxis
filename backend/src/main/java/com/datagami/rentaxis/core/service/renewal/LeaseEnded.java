package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;

/**
 * Break-it R3 portal3 F8: whether a contract is over, for renewal purposes. A lease
 * past its end date is over even while it still reads ACTIVE (no expiry job ran);
 * a terminated, expired or closed one is over from the day it stopped.
 */
public final class LeaseEnded {

    private static final Set<LeaseStatus> CLOSED_STATUSES =
            EnumSet.of(LeaseStatus.TERMINATED, LeaseStatus.EXPIRED, LeaseStatus.CLOSED);

    private LeaseEnded() {
    }

    public static boolean hasEnded(Lease lease, LocalDate today) {
        if (lease.getStatus() != null && CLOSED_STATUSES.contains(lease.getStatus())) {
            return true;
        }
        return lease.getEndDate() != null && lease.getEndDate().isBefore(today);
    }

    /** Whole days since the contract stopped (0 = today); 0 for one still running. */
    public static long daysSinceEnded(Lease lease, LocalDate today) {
        if (!hasEnded(lease, today)) {
            return 0;
        }
        LocalDate stopped = lease.getEndDate();
        if (lease.getTerminatedOn() != null && (stopped == null || lease.getTerminatedOn().isBefore(stopped))) {
            stopped = lease.getTerminatedOn();
        }
        if (stopped == null || stopped.isAfter(today)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(stopped, today);
    }
}
