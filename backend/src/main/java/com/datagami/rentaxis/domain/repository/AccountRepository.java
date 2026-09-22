package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Tenant-aware lookup by id, for an account id that arrived on a request body (a
     * lease line's credit account, a lease's receivable override, an opening-balance
     * row) — a caller in tenant A must not be able to point their books at a leaf
     * belonging to tenant B.
     *
     * <p><b>Corrected:</b> this used to say that Spring Data's {@code findById}
     * bypasses Hibernate filters and that this JPQL query was therefore the only safe
     * finder. That is true of a plain Hibernate 7 mapping but <em>not</em> of ours:
     * {@code BaseTenantEntity} declares {@code @FilterDef(..., applyToLoadByKey = true)}
     * precisely so that primary-key loads are filtered too, which was measured — a
     * mutation swapping this finder for {@code findById} in
     * {@code OpeningBalanceService.setRow} fails no test, because both are filtered
     * once the aspect has enabled the filter around the repository call.</p>
     *
     * <p>Prefer this one anyway. It is the finder that still scopes when the filter is
     * <em>not</em> enabled — outside a transaction, or with an empty
     * {@code TenantContextHolder} — and that costs nothing.</p>
     */
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdScopedToTenant(@Param("id") UUID id);

    Optional<Account> findByCode(String code);

    Optional<Account> findByCodeAndTenantId(String code, UUID tenantId);

    List<Account> findByAccountType(AccountType accountType);

    List<Account> findByParentIsNullOrderByDisplayOrderAscCodeAsc();

    List<Account> findByParent_IdOrderByDisplayOrderAscCodeAsc(UUID parentId);

    boolean existsByParent_Id(UUID parentId);

    Optional<Account> findByNameAndParent_Id(String name, UUID parentId);

    List<Account> findByProperty_Id(UUID propertyId);

    /**
     * Highest numeric code in this tenant (codes like "A-02-01" are ignored).
     * Native query: the Hibernate tenant filter does not apply to it, so the
     * scoping is the explicit {@code tenant_id = :tenantId} in the SQL below —
     * do not drop that predicate.
     */
    @Query(value = "select max(code::bigint) from accounts where tenant_id = :tenantId and code ~ '^[0-9]+$'",
            nativeQuery = true)
    Long findMaxNumericCode(@Param("tenantId") UUID tenantId);
}
