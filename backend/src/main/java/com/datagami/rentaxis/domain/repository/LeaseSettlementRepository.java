package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LeaseSettlementRepository extends JpaRepository<LeaseSettlement, UUID> {
    Optional<LeaseSettlement> findByLeaseId(UUID leaseId);
}
