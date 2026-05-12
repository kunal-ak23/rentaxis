package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseRepository extends JpaRepository<Lease, UUID> {
    List<Lease> findByTenantId(UUID tenantId);
    Page<Lease> findByTenantId(UUID tenantId, Pageable pageable);

    List<Lease> findByUnitId(UUID unitId);

    List<Lease> findByUnitIdAndStatus(UUID unitId, LeaseStatus status);

    List<Lease> findByRenterId(UUID renterId);

    @Query("SELECT l FROM Lease l WHERE l.status IN :statuses AND l.endDate < :date")
    List<Lease> findByStatusInAndEndDateBefore(
            @Param("statuses") List<LeaseStatus> statuses,
            @Param("date") LocalDate date);

    @Query("SELECT l FROM Lease l WHERE l.status = :status AND l.endDate BETWEEN :from AND :to")
    List<Lease> findByStatusAndEndDateBetween(
            @Param("status") LeaseStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT l FROM Lease l WHERE l.unit.property.id = :propertyId")
    List<Lease> findByUnitPropertyId(@Param("propertyId") UUID propertyId);

    List<Lease> findByStatus(LeaseStatus status);

    @Query("SELECT COALESCE(MAX(l.contractNumber), 0) FROM Lease l WHERE l.tenantId = :tenantId")
    Long findMaxContractNumberForTenant(@Param("tenantId") UUID tenantId);

    /**
     * Tenant-aware lookup by id. Unlike Spring Data's default {@code findById},
     * this goes through JPQL — which applies Hibernate {@code @Filter}
     * annotations. The default {@code findById} bypasses filters in Hibernate
     * 7 (unless {@code applyToLoadByKey=true} is set on the filter), which
     * would let a caller in tenant A operate on a lease id from tenant B.
     * Use this anywhere a caller-supplied lease id must be scoped to the
     * current tenant.
     */
    @Query("SELECT l FROM Lease l WHERE l.id = :id")
    Optional<Lease> findByIdScopedToTenant(@Param("id") UUID id);

    @Query("""
        SELECT l FROM Lease l
        WHERE l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
          AND l.endDate <= :cutoff
          AND NOT EXISTS (
            SELECT 1 FROM RenewalOpportunity o
            WHERE o.lease.id = l.id
              AND o.stage IN (com.datagami.rentaxis.domain.entity.enums.RenewalStage.OPEN,
                              com.datagami.rentaxis.domain.entity.enums.RenewalStage.INTENT_CAPTURED)
          )
    """)
    List<Lease> findActiveLeasesEnteringRenewalWindow(@Param("cutoff") LocalDate cutoff);
}
