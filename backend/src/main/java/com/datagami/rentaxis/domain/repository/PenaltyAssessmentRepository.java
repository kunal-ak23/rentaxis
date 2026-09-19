package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PenaltyAssessment;
import com.datagami.rentaxis.domain.entity.enums.PenaltyAssessmentStatus;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PenaltyAssessmentRepository extends JpaRepository<PenaltyAssessment, UUID> {

    /**
     * The row, locked for the decision about to be taken.
     *
     * <p>Approving posts a journal and creates a register row. Two accountants
     * clicking <em>Approve</em> on the same proposal under {@code READ_COMMITTED}
     * both read {@code PROPOSED}, both pass the guard, and the renter is charged
     * the fine twice with two collection rows to match. NOWAIT so the loser is an
     * immediate "try again" rather than a connection parked behind someone's open
     * tab — the same shape as {@code ChequeRepository.findByIdForUpdate}.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select p from PenaltyAssessment p where p.id = :id")
    Optional<PenaltyAssessment> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The worklist. Every filter is optional; the casts let Postgres infer a type
     * for the bare {@code is null} test, which it otherwise rejects once a caller
     * passes a non-null filter.
     *
     * <p>{@code unrestricted} is how a property manager is kept inside their own
     * buildings. The caller passes {@code false} with the property ids they were
     * assigned; {@code propertyIds} is never empty on that path, because a manager
     * assigned to nothing is answered without a query at all.</p>
     */
    @Query("""
        select p from PenaltyAssessment p
        where (cast(:leaseId as java.util.UUID) is null or p.lease.id = :leaseId)
          and (cast(:status as string) is null or p.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or p.property.id = :propertyId)
          and (:unrestricted = true or p.property.id in :propertyIds)
        """)
    Page<PenaltyAssessment> search(@Param("leaseId") UUID leaseId,
                                   @Param("status") PenaltyAssessmentStatus status,
                                   @Param("propertyId") UUID propertyId,
                                   @Param("unrestricted") boolean unrestricted,
                                   @Param("propertyIds") Collection<UUID> propertyIds,
                                   Pageable pageable);

    /** The renter portal's list: what they were actually charged, never what was merely proposed. */
    List<PenaltyAssessment> findByRenter_IdAndStatusOrderByProposedAtAsc(UUID renterId, PenaltyAssessmentStatus status);

    /**
     * Whether this cheque already has a live proposal of this kind — the guard that
     * stops a rule firing twice over one returned instrument. WAIVED and REVERSED
     * are deliberately not "live": finance having declined a fine once is not a
     * reason the same cheque bouncing again may never be proposed.
     */
    boolean existsByCheque_IdAndReasonAndStatusIn(UUID chequeId, PenaltyReason reason,
                                                  Collection<PenaltyAssessmentStatus> statuses);
}
