package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantFiscalSettingsRepository extends JpaRepository<TenantFiscalSettings, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TenantFiscalSettings s where s.tenantId = :tenantId")
    Optional<TenantFiscalSettings> findForUpdate(@Param("tenantId") UUID tenantId);
}
