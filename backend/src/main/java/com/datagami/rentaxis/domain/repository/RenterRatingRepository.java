package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenterRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenterRatingRepository extends JpaRepository<RenterRating, UUID> {

    Optional<RenterRating> findByTenantIdAndRenterId(UUID tenantId, UUID renterId);

    List<RenterRating> findByTenantIdAndRenterIdIn(UUID tenantId, Collection<UUID> renterIds);
}
