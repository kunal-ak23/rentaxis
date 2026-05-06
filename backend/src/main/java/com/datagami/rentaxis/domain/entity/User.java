package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@AttributeOverride(name = "tenantId", column = @Column(name = "tenant_id", nullable = true))
public class User extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "welcomed_at")
    private Instant welcomedAt;

    // Optional override of tenantId from BaseTenantEntity
    // If a user is SUPER_ADMIN, tenantId might be null
}
