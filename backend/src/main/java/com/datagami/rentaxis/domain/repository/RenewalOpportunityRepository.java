package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenewalOpportunityRepository extends JpaRepository<RenewalOpportunity, UUID> {

    Optional<RenewalOpportunity> findByLeaseIdAndStageIn(UUID leaseId, List<RenewalStage> stages);

    @Query("SELECT o FROM RenewalOpportunity o WHERE o.tenantId = :tenantId AND o.stage IN :stages")
    List<RenewalOpportunity> findByTenantIdAndStageIn(@Param("tenantId") UUID tenantId, @Param("stages") List<RenewalStage> stages);

    /**
     * Cross-tenant lookup used ONLY by the public renewal-intent endpoint
     * (where the tenant context is established FROM the opportunity itself).
     * Bypasses the Hibernate tenant filter via a native query so it returns
     * regardless of TenantContextHolder.
     */
    @Query(value = "SELECT * FROM renewal_opportunities WHERE id = :id", nativeQuery = true)
    Optional<RenewalOpportunity> findByIdAcrossTenants(@Param("id") UUID id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM RenewalOpportunity o WHERE o.id = :id")
    Optional<RenewalOpportunity> findByIdForUpdate(@Param("id") UUID id);

    /** Scale PR C: the opportunities of a lease that carry the "not renewing" mark (any stage). */
    @Query("SELECT o FROM RenewalOpportunity o WHERE o.lease.id = :leaseId AND o.notRenewingAt IS NOT NULL")
    List<RenewalOpportunity> findMarkedNotRenewing(@Param("leaseId") UUID leaseId);

    /**
     * Scale PR C: the "not renewing" marks on a page of leases — lease id, when, by whom, note —
     * for the list's rows. {@code tenantId} is bound: this must not lean on the tenant filter.
     */
    @Query("""
        SELECT o.lease.id, o.notRenewingAt, o.notRenewingBy, o.notRenewingNote FROM RenewalOpportunity o
        WHERE o.tenantId = :tenantId AND o.lease.id IN :leaseIds AND o.notRenewingAt IS NOT NULL
        ORDER BY o.notRenewingAt DESC
        """)
    List<Object[]> findMarksForLeases(@Param("tenantId") UUID tenantId, @Param("leaseIds") java.util.Collection<UUID> leaseIds);
}
