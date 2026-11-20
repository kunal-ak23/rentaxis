package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenterRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenterRatingSnapshotRepository extends JpaRepository<RenterRatingSnapshot, UUID> {

    List<RenterRatingSnapshot> findTop12ByTenantIdAndRenterIdOrderByMonthDesc(UUID tenantId, UUID renterId);

    Optional<RenterRatingSnapshot> findByTenantIdAndRenterIdAndMonth(UUID tenantId, UUID renterId, LocalDate month);
}
