package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RentSegmentRepository extends JpaRepository<RentSegment, UUID> {

    /** The lease's segments oldest first — the base term, then one per extension. */
    List<RentSegment> findByLease_IdOrderByFromDateAsc(UUID leaseId);

    /** The segments that still describe the contract: ACTIVE, and TRUNCATED after a termination. */
    List<RentSegment> findByLease_IdAndStatusInOrderByFromDateAsc(UUID leaseId, Collection<SegmentStatus> statuses);

    /**
     * Whether this line already has a segment that has not been retired — the
     * guard that makes {@code buildForLease} safe to call twice and makes
     * {@code rebuildAfterAmend} work by cancelling first and rebuilding after.
     */
    boolean existsByLeaseLine_IdAndStatusIn(UUID leaseLineId, Collection<SegmentStatus> statuses);
}
