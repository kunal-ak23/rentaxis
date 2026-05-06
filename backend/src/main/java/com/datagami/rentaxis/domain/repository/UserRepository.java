package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByInviteToken(String token);

    @Modifying
    @Query("""
            UPDATE User u
            SET u.passwordHash = :hash,
                u.inviteToken = NULL,
                u.inviteTokenExpiresAt = NULL
            WHERE u.inviteToken = :token
              AND u.inviteTokenExpiresAt IS NOT NULL
              AND u.inviteTokenExpiresAt > :now
            """)
    int redeemInviteToken(@Param("token") String token,
                          @Param("hash") String hash,
                          @Param("now") Instant now);

    List<User> findByTenantId(UUID tenantId);

    List<User> findByRole(UserRole role);

    List<User> findByTenantIdAndRole(UUID tenantId, UserRole role);
}
