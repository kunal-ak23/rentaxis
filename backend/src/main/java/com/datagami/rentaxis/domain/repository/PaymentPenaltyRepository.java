package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentPenaltyRepository extends JpaRepository<PaymentPenalty, UUID> {

    List<PaymentPenalty> findByLeaseIdOrderByCreatedAtAsc(UUID leaseId);

    Optional<PaymentPenalty> findByPaymentScheduleId(UUID paymentScheduleId);

    List<PaymentPenalty> findByLeaseIdAndWaivedFalse(UUID leaseId);

    List<PaymentPenalty> findByPenaltyTypeAndClearedAtIsNull(String penaltyType);

    // Paged list filtered by optional leaseId and status ("open" / "cleared" / "all")
    @Query("""
        SELECT p FROM PaymentPenalty p
        WHERE (:leaseId IS NULL OR p.leaseId = :leaseId)
          AND (:openOnly = false OR p.clearedAt IS NULL)
          AND (:clearedOnly = false OR p.clearedAt IS NOT NULL)
        ORDER BY p.createdAt ASC
        """)
    Page<PaymentPenalty> findFiltered(
            @Param("leaseId") UUID leaseId,
            @Param("openOnly") boolean openOnly,
            @Param("clearedOnly") boolean clearedOnly,
            Pageable pageable);

    // Filtered for renter: only leases in the allowed list
    @Query("""
        SELECT p FROM PaymentPenalty p
        WHERE p.leaseId IN :leaseIds
          AND (:leaseId IS NULL OR p.leaseId = :leaseId)
          AND (:openOnly = false OR p.clearedAt IS NULL)
          AND (:clearedOnly = false OR p.clearedAt IS NOT NULL)
        ORDER BY p.createdAt ASC
        """)
    Page<PaymentPenalty> findFilteredForRenter(
            @Param("leaseIds") List<UUID> leaseIds,
            @Param("leaseId") UUID leaseId,
            @Param("openOnly") boolean openOnly,
            @Param("clearedOnly") boolean clearedOnly,
            Pageable pageable);
}
