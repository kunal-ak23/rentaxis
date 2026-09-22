package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyAccountMappingRepository extends JpaRepository<PropertyAccountMapping, UUID> {
    Optional<PropertyAccountMapping> findByPropertyIdAndRole(UUID propertyId, AccountRole role);
    List<PropertyAccountMapping> findByPropertyId(UUID propertyId);
    List<PropertyAccountMapping> findByPropertyIdAndRoleIn(UUID propertyId, Collection<AccountRole> roles);
    boolean existsByAccount_Id(UUID accountId);
}
