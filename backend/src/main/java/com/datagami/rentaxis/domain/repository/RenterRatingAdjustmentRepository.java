package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenterRatingAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenterRatingAdjustmentRepository extends JpaRepository<RenterRatingAdjustment, UUID> {

    List<RenterRatingAdjustment> findByTenantIdAndRenterIdInAndRemovedAtIsNull(UUID tenantId, Collection<UUID> renterIds);

    Optional<RenterRatingAdjustment> findByIdAndTenantIdAndRenterId(UUID id, UUID tenantId, UUID renterId);
}
