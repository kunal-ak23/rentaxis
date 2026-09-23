package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseAddendumRepository extends JpaRepository<LeaseAddendum, UUID> {
    List<LeaseAddendum> findByLease_IdOrderByCreatedAtAsc(UUID leaseId);

    /** Scoped to the lease in the path, so an addendum id from another lease is a 404, not an edit. */
    Optional<LeaseAddendum> findByIdAndLease_Id(UUID id, UUID leaseId);
}
