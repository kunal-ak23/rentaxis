package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VatTaxPoint;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VatTaxPointRepository extends JpaRepository<VatTaxPoint, UUID> {

    /** The lease's whole schedule, oldest tax point first. */
    List<VatTaxPoint> findByLeaseIdOrderByTaxPointDateAscCreatedAtAsc(UUID leaseId);

    List<VatTaxPoint> findByLeaseIdAndStatusOrderByTaxPointDateAsc(UUID leaseId, VatTaxPointStatus status);

    /** The instalment's live (PLANNED or POSTED) point, if it has one. */
    @Query("select p from VatTaxPoint p where p.chequeId = :chequeId and p.status <> "
            + "com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus.CANCELLED")
    Optional<VatTaxPoint> findLiveByChequeId(@Param("chequeId") UUID chequeId);

    List<VatTaxPoint> findByChequeIdIn(Collection<UUID> chequeIds);

    boolean existsByLeaseIdAndStatus(UUID leaseId, VatTaxPointStatus status);

    /**
     * Everything due by {@code to}, oldest first — the nightly job's candidates.
     * The tenant is a parameter, not only the ambient filter, for the reason
     * {@code RecognitionEntryRepository} gives for its own candidate query.
     */
    List<VatTaxPoint> findByTenantIdAndStatusAndTaxPointDateLessThanEqualOrderByTaxPointDateAscCreatedAtAsc(
            UUID tenantId, VatTaxPointStatus status, LocalDate to);

    /** What {@code lockThrough} refuses on: a PLANNED point the lock would strand. */
    Optional<VatTaxPoint> findFirstByTenantIdAndStatusAndTaxPointDateLessThanEqualOrderByTaxPointDateAsc(
            UUID tenantId, VatTaxPointStatus status, LocalDate through);
}
