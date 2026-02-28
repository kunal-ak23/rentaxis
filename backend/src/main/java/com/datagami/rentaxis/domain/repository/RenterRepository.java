package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Renter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RenterRepository extends JpaRepository<Renter, UUID> {
    List<Renter> findByTenantId(UUID tenantId);

    Optional<Renter> findByUserId(UUID userId);
}
