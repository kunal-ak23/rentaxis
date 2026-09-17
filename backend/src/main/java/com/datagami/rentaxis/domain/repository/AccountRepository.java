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
