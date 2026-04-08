package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UnitListingRepository extends JpaRepository<UnitListing, UUID>,
        JpaSpecificationExecutor<UnitListing> {

    Optional<UnitListing> findBySlugAndTenantId(String slug, UUID tenantId);

    boolean existsBySlugAndTenantId(String slug, UUID tenantId);

    Page<UnitListing> findByTenantIdAndStatus(UUID tenantId, ListingStatus status, Pageable pageable);

    Page<UnitListing> findByTenantId(UUID tenantId, Pageable pageable);

    Optional<UnitListing> findByUnitId(UUID unitId);

    java.util.List<UnitListing> findAllByIdIn(java.util.Collection<UUID> ids);

    java.util.List<UnitListing> findByStatus(ListingStatus status);
}
