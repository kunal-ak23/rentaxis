package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UserTenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserTenantMembershipRepository
        extends JpaRepository<UserTenantMembership, UserTenantMembership.UserTenantMembershipId> {

    List<UserTenantMembership> findByUserId(UUID userId);

    List<UserTenantMembership> findByTenantId(UUID tenantId);

    void deleteByUserIdAndTenantId(UUID userId, UUID tenantId);

    boolean existsByUserIdAndTenantId(UUID userId, UUID tenantId);
}
