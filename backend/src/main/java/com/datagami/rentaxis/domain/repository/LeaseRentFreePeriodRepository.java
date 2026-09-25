package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseRentFreePeriod;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseRentFreePeriodRepository extends JpaRepository<LeaseRentFreePeriod, UUID> {
    List<LeaseRentFreePeriod> findByLease_IdOrderByFromDateAsc(UUID leaseId);

    /** {@link #findByLease_IdOrderByFromDateAsc} for a page of leases. */
    List<LeaseRentFreePeriod> findByLease_IdInOrderByFromDateAsc(java.util.Collection<UUID> leaseIds);
}
