package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantDefaultAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantDefaultAccountMappingRepository extends JpaRepository<TenantDefaultAccountMapping, UUID> {
    Optional<TenantDefaultAccountMapping> findByRole(AccountRole role);
    List<TenantDefaultAccountMapping> findByRoleIn(Collection<AccountRole> roles);
    List<TenantDefaultAccountMapping> findAllByOrderByRoleAsc();
}
