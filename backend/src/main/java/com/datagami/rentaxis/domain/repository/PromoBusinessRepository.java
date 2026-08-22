package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoBusiness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoBusinessRepository extends JpaRepository<PromoBusiness, UUID> {

    Page<PromoBusiness> findByTenantId(UUID tenantId, Pageable pageable);

    List<PromoBusiness> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    List<PromoBusiness> findByIdIn(List<UUID> ids);
}
