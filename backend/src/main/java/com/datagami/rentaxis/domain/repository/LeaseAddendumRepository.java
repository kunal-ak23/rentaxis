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

    /**
     * F14-33: the lease's registered addendum Ejari numbers, latest first — the
     * first is the lease's current registration (see {@code LeaseDTO.currentEjari}).
     */
    @org.springframework.data.jpa.repository.Query("""
            select a.ejariNumber from LeaseAddendum a
            where a.lease.id = :leaseId and a.ejariNumber is not null and trim(a.ejariNumber) <> ''
            order by a.createdAt desc, a.id desc""")
    List<String> findRegisteredEjariLatestFirst(@org.springframework.data.repository.query.Param("leaseId") UUID leaseId);
}
