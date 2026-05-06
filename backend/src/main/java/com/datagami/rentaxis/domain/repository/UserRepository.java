package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByInviteToken(String token);

    List<User> findByTenantId(UUID tenantId);

    List<User> findByRole(UserRole role);

    List<User> findByTenantIdAndRole(UUID tenantId, UserRole role);
}
