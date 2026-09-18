package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ChargeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every method here is scoped to the current tenant by the Hibernate
 * {@code tenantFilter} that {@code TenantAspect} enables around repository
 * calls — so {@code findByCode("RENT")} means "this tenant's RENT", and two
 * tenants each owning a row with that code is normal.
 */
@Repository
public interface ChargeTypeRepository extends JpaRepository<ChargeType, UUID> {

    Optional<ChargeType> findByCode(String code);

    List<ChargeType> findAllByOrderByDisplayOrderAscCodeAsc();

    List<ChargeType> findByActiveTrueOrderByDisplayOrderAscCodeAsc();
}
