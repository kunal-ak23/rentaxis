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
}
