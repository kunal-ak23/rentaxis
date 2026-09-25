package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseAssignmentRepository extends JpaRepository<LeaseAssignment, UUID> {

    List<LeaseAssignment> findByLeaseIdOrderByCreatedAtAsc(UUID leaseId);

    Optional<LeaseAssignment> findByIdAndLeaseId(UUID id, UUID leaseId);

    boolean existsByLeaseIdAndStatus(UUID leaseId, String status);

    /** PR #359 R1: the posted hand-overs of a lease, oldest first. */
    List<LeaseAssignment> findByLeaseIdAndStatusOrderByEffectiveDateAsc(UUID leaseId, String status);

    /** PR #359 R1: leases a renter handed over (their history stays readable to them). */
    List<LeaseAssignment> findByFromRenterIdAndStatus(UUID fromRenterId, String status);
}
