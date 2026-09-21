package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface RecognitionEntryRepository extends JpaRepository<RecognitionEntry, UUID> {

    /** The lease's whole schedule, in the order the lease page prints it. */
    List<RecognitionEntry> findByLease_IdOrderByPeriodStartAsc(UUID leaseId);

    List<RecognitionEntry> findByLease_IdAndStatusInOrderByPeriodStartAsc(
            UUID leaseId, Collection<RecognitionStatus> statuses);

    /** One segment's rows — what a termination re-slices. */
    List<RecognitionEntry> findBySegment_IdOrderByPeriodStartAsc(UUID segmentId);

    /**
     * Everything due to be recognised by {@code to}, oldest period first — the
     * nightly run's candidate list (spec §8.4).
     *
     * <p>The tenant is a parameter rather than an ambient filter on purpose. The
     * job walks the tenants one at a time and sets the context itself, and
     * {@code TenantAspect} only enables the Hibernate filter around a repository
     * call made inside a transaction; a query that depended on the filter alone
     * would quietly return every tenant's rows the first time somebody called it
     * from outside one. With the id in the where clause the two agree, and if the
     * filter is on as well it narrows the same rows a second time.</p>
     */
    List<RecognitionEntry> findByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
            UUID tenantId, RecognitionStatus status, LocalDate to);
}
