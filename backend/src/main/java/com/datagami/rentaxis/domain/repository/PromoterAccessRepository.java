package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoterAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant in every signature: the boundary is the caller's, never the row's. */
@Repository
public interface PromoterAccessRepository extends JpaRepository<PromoterAccess, UUID> {

    Optional<PromoterAccess> findByTenantIdAndUserId(UUID tenantId, UUID userId);

    List<PromoterAccess> findByTenantIdOrderByCreatedAtAscUserIdAsc(UUID tenantId);
}
