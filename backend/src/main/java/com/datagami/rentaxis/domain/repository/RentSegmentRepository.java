package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RentSegmentRepository extends JpaRepository<RentSegment, UUID> {

    /**
     * The lease's segments oldest first — the base term, then one per extension.
     *
     * <p>Ties on {@code fromDate} are real: an amendment dated a segment's first day
     * cancels it and writes its replacement on the same date. "order by from_date"
     * alone leaves those two in whatever order the heap or the index hands back, which
     * moves with vacuum and page fill — so the order a segment was made breaks the tie,
     * and the id after it (RecognitionServiceIT flake, 2026-10).</p>
     */
    @Query("select s from RentSegment s where s.lease.id = :leaseId order by s.fromDate asc, s.createdAt asc, s.id asc")
    List<RentSegment> findByLease_IdOrderByFromDateAsc(@Param("leaseId") UUID leaseId);

    /** The segments that still describe the contract: ACTIVE, and TRUNCATED after a termination. Ties as above. */
    @Query("select s from RentSegment s where s.lease.id = :leaseId and s.status in :statuses"
            + " order by s.fromDate asc, s.createdAt asc, s.id asc")
    List<RentSegment> findByLease_IdAndStatusInOrderByFromDateAsc(@Param("leaseId") UUID leaseId,
                                                                  @Param("statuses") Collection<SegmentStatus> statuses);

    /**
     * Whether this line already has a segment that has not been retired — the
     * guard that makes {@code buildForLease} safe to call twice and makes
     * {@code rebuildAfterAmend} work by cancelling first and rebuilding after.
     */
    boolean existsByLeaseLineIdAndStatusIn(UUID leaseLineId, Collection<SegmentStatus> statuses);
}
