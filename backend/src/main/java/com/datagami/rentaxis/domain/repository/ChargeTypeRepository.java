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

    /**
     * The same lookup, ignoring case — what a spreadsheet column needs (review R8).
     *
     * <p>A finder rather than {@code toUpperCase()} at the call sites: the codes are
     * upper-case by convention and not by constraint, so an accountant who typed
     * {@code rent} and a tenant who created {@code Cooling} both have to resolve, and
     * doing that by normalising at each call site is how one of three call sites ends
     * up not doing it.</p>
     */
    Optional<ChargeType> findByCodeIgnoreCase(String code);

    List<ChargeType> findAllByOrderByDisplayOrderAscCodeAsc();

    List<ChargeType> findByActiveTrueOrderByDisplayOrderAscCodeAsc();
}
