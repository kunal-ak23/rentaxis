package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoterBusiness;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Tenant in every signature: the boundary is the caller's, never the row's. */
@Repository
public interface PromoterBusinessRepository extends JpaRepository<PromoterBusiness, PromoterBusiness.Key> {

    @Query("SELECT pb.businessId FROM PromoterBusiness pb WHERE pb.tenantId = :tenantId AND pb.userId = :userId")
    List<UUID> findBusinessIds(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    boolean existsByTenantIdAndUserIdAndBusinessId(UUID tenantId, UUID userId, UUID businessId);

    List<PromoterBusiness> findByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);

    /**
     * Bulk, so it runs at once (a replace inserts the new set right after).
     * {@code clearAutomatically}: a grant loaded earlier in the transaction would
     * otherwise stay managed and make the re-insert of the same key a no-op merge.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PromoterBusiness pb WHERE pb.tenantId = :tenantId AND pb.userId = :userId")
    void deleteByTenantIdAndUserId(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
