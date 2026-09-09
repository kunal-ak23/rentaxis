package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseInteraction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;

public interface LeaseInteractionRepository extends JpaRepository<LeaseInteraction, UUID> {

    @Query("""
        SELECT i FROM LeaseInteraction i
        WHERE i.lease.id = :leaseId AND i.deletedAt IS NULL
        ORDER BY i.occurredAt DESC
    """)
    Page<LeaseInteraction> findActiveByLeaseId(@Param("leaseId") UUID leaseId, Pageable pageable);

    @Query("""
        SELECT i FROM LeaseInteraction i
        WHERE i.tenantId = :tenantId
          AND i.followUpDate IS NOT NULL
          AND i.followUpDate <= :date
          AND i.deletedAt IS NULL
          AND (i.outcome IS NULL OR i.outcome <> com.datagami.rentaxis.domain.entity.enums.InteractionOutcome.POSITIVE)
        ORDER BY i.followUpDate ASC
    """)
    List<LeaseInteraction> findPendingFollowUps(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Modifying
    @Query("UPDATE LeaseInteraction i SET i.createdBy = NULL WHERE i.createdBy = :userId")
    void detachCreatedBy(@Param("userId") UUID userId);


    /**
     * Hard-deletes every interaction on a lease, including soft-deleted rows.
     *
     * <p>lease_interactions.lease_id is NOT NULL with no ON DELETE clause, and
     * softDelete only stamps deletedAt — the row stays and keeps holding the
     * foreign key. So a DRAFT lease with a single note could not be deleted
     * through the API at all.
     */
    @Modifying
    @Query("DELETE FROM LeaseInteraction i WHERE i.lease.id = :leaseId")
    void deleteByLeaseId(@Param("leaseId") UUID leaseId);
}
