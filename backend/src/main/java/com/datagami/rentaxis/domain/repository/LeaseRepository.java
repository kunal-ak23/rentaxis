package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseRepository extends JpaRepository<Lease, UUID> {
    List<Lease> findByTenantId(UUID tenantId);

    List<Lease> findByUnitId(UUID unitId);

    List<Lease> findByRenterId(UUID renterId);

    @Query("SELECT l FROM Lease l WHERE l.status IN :statuses AND l.endDate < :date")
    List<Lease> findByStatusInAndEndDateBefore(
            @Param("statuses") List<LeaseStatus> statuses,
            @Param("date") LocalDate date);

    @Query("SELECT l FROM Lease l WHERE l.status = :status AND l.endDate BETWEEN :from AND :to")
    List<Lease> findByStatusAndEndDateBetween(
            @Param("status") LeaseStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT l FROM Lease l WHERE l.unit.property.id = :propertyId")
    List<Lease> findByUnitPropertyId(@Param("propertyId") UUID propertyId);

    List<Lease> findByStatus(LeaseStatus status);
}
