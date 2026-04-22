package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentScheduleRepository extends JpaRepository<PaymentSchedule, UUID> {

    List<PaymentSchedule> findByLeaseId(UUID leaseId);

    List<PaymentSchedule> findByPropertyId(UUID propertyId);

    List<PaymentSchedule> findByStatus(PaymentStatus status);

    List<PaymentSchedule> findByLeaseIdAndStatus(UUID leaseId, PaymentStatus status);

    @Query("SELECT ps FROM PaymentSchedule ps WHERE ps.status IN ('PENDING', 'COLLECTED') AND ps.dueDate < :date")
    List<PaymentSchedule> findOverdue(@Param("date") LocalDate date);

    List<PaymentSchedule> findByPropertyIdAndStatusIn(UUID propertyId, List<PaymentStatus> statuses);

    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND (:status IS NULL OR ps.status = :status)
        """)
    Page<PaymentSchedule> findFiltered(
            @Param("propertyId") UUID propertyId,
            @Param("status") PaymentStatus status,
            Pageable pageable);

    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND (:status IS NULL OR ps.status = :status)
        ORDER BY ps.dueDate DESC
        """)
    List<PaymentSchedule> findForRenterSearch(
            @Param("propertyId") UUID propertyId,
            @Param("status") PaymentStatus status);
}
