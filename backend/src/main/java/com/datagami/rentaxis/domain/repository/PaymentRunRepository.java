package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRunRepository extends JpaRepository<PaymentRun, UUID> {
    List<PaymentRun> findAllByOrderByCreatedAtAsc();

    /** Scale P1-3: payment runs, filtered by status and payment date, paged. */
    @org.springframework.data.jpa.repository.Query("""
        select r from PaymentRun r
        where r.tenantId = :tenantId
          and (cast(:status as string) is null or r.status = :status)
          and (cast(:from as LocalDate) is null or r.paymentDate >= :from)
          and (cast(:to as LocalDate) is null or r.paymentDate <= :to)
        """)
    org.springframework.data.domain.Page<PaymentRun> searchPaged(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("status") PaymentRun.Status status,
            @org.springframework.data.repository.query.Param("from") java.time.LocalDate from,
            @org.springframework.data.repository.query.Param("to") java.time.LocalDate to,
            org.springframework.data.domain.Pageable pageable);
}
