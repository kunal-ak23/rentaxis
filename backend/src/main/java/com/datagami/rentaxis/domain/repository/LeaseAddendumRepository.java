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

    /** F14-32: whether a credit addendum has cut this lease's lines. */
    boolean existsByLease_IdAndKind(UUID leaseId, String kind);

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

    /** {@link #findRegisteredEjariLatestFirst} for a page of leases: (leaseId, ejari), latest first per lease. */
    @org.springframework.data.jpa.repository.Query("""
            select a.lease.id, a.ejariNumber from LeaseAddendum a
            where a.lease.id in :leaseIds and a.ejariNumber is not null and trim(a.ejariNumber) <> ''
            order by a.createdAt desc, a.id desc""")
    List<Object[]> findRegisteredEjariLatestFirst(
            @org.springframework.data.repository.query.Param("leaseIds") java.util.Collection<UUID> leaseIds);

    /** The leases of {@code leaseIds} carrying at least one addendum of {@code kind}. */
    @org.springframework.data.jpa.repository.Query("""
            select distinct a.lease.id from LeaseAddendum a where a.lease.id in :leaseIds and a.kind = :kind""")
    List<UUID> leaseIdsWithKind(@org.springframework.data.repository.query.Param("leaseIds") java.util.Collection<UUID> leaseIds,
                                @org.springframework.data.repository.query.Param("kind") String kind);
}
