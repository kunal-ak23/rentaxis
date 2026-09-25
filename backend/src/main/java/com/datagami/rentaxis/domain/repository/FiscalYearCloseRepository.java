package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.FiscalYearClose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FiscalYearCloseRepository extends JpaRepository<FiscalYearClose, UUID> {

    /** Every close of the tenant, newest year first (JPQL: the tenant filter applies inside a transaction). */
    List<FiscalYearClose> findAllByOrderByFiscalYearDescClosedAtDesc();

    @Query("select c from FiscalYearClose c where c.fiscalYear = :fy and c.status = com.datagami.rentaxis.domain.entity.enums.FiscalYearCloseStatus.CLOSED")
    Optional<FiscalYearClose> findClosed(@Param("fy") int fiscalYear);

    /**
     * The end of the latest CLOSED year for a tenant, or null. Native with an
     * explicit tenant: PostingService asks it on every import or OB post, and that
     * path must not depend on the Hibernate filter being enabled.
     */
    @Query(value = "select max(period_end) from fiscal_year_closes where tenant_id = :tenantId and status = 'CLOSED'",
            nativeQuery = true)
    LocalDate latestClosedPeriodEnd(@Param("tenantId") UUID tenantId);
}
