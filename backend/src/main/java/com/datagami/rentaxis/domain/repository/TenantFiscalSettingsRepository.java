package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantFiscalSettingsRepository extends JpaRepository<TenantFiscalSettings, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TenantFiscalSettings s where s.tenantId = :tenantId")
    Optional<TenantFiscalSettings> findForUpdate(@Param("tenantId") UUID tenantId);

    /**
     * Creates the tenant's default settings row if it has none.
     *
     * <p>Not {@code save()}: the primary key is assigned, so Spring Data issues a
     * merge, and two tenants' first postings racing on the same row either collide
     * on the primary key — aborting the caller's whole transaction in Postgres — or
     * overwrite each other. {@code ON CONFLICT DO NOTHING} yields to the winner.
     *
     * <p>Runs in its own transaction, because the row is created lazily on first
     * access and that first access is often a read — {@code fiscalYearOf} and
     * {@code assertOpen} are read-only, and Postgres refuses an INSERT on a
     * read-only connection.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(value = """
        insert into tenant_fiscal_settings (tenant_id, fiscal_year_start_month, updated_at)
        values (:tenantId, 1, now())
        on conflict (tenant_id) do nothing
        """, nativeQuery = true)
    int insertDefaultIfAbsent(@Param("tenantId") UUID tenantId);
}
