package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RenterRatingSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RenterRatingSnapshotRepository extends JpaRepository<RenterRatingSnapshot, UUID> {

    List<RenterRatingSnapshot> findTop12ByTenantIdAndRenterIdOrderByMonthDesc(UUID tenantId, UUID renterId);
}
