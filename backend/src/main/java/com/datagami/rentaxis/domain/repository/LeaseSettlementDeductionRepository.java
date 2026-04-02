package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaseSettlementDeductionRepository extends JpaRepository<LeaseSettlementDeduction, UUID> {
    List<LeaseSettlementDeduction> findBySettlementIdOrderByCreatedAtAsc(UUID settlementId);
}
