package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VatReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VatReturnRepository extends JpaRepository<VatReturn, UUID> {

    List<VatReturn> findAllByOrderByPeriodStartDescFiledAtDesc();

    Optional<VatReturn> findFirstByPeriodStartAndStatus(LocalDate periodStart, String status);

    /** The FILED return whose period holds {@code date}, if any. Native with the tenant bound: callable outside the filter. */
    @Query(value = """
        select * from vat_returns
        where tenant_id = :tenantId and status = 'FILED' and :date between period_start and period_end
        limit 1
        """, nativeQuery = true)
    Optional<VatReturn> filedCovering(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    /** The latest FILED period end, or null (the first date a correction may be dated after). */
    @Query(value = "select max(period_end) from vat_returns where tenant_id = :tenantId and status = 'FILED'", nativeQuery = true)
    LocalDate latestFiledEnd(@Param("tenantId") UUID tenantId);
}
