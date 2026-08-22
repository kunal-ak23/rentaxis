package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoBusiness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoBusinessRepository extends JpaRepository<PromoBusiness, UUID> {

    Page<PromoBusiness> findByTenantId(UUID tenantId, Pageable pageable);

    List<PromoBusiness> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    /**
     * Tenant in the signature, not left to the ambient Hibernate filter. The
     * renter feed's privacy boundary should not rest on a thread-local that a
     * future caller without tenant context (a scheduler, a warmup job) could
     * silently bypass.
     */
    List<PromoBusiness> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);
}
