package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeaseChargeRepository extends JpaRepository<LeaseCharge, UUID> {
    List<LeaseCharge> findByLeaseId(UUID leaseId);
}
