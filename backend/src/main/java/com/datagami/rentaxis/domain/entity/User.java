package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // Uniqueness is enforced at the DB level (migration 59) via two partial
    // indexes: (tenant_id, email) for tenanted users and (email) for
    // SUPER_ADMINs. We deliberately omit `unique = true` here so JPA doesn't
    // re-add the global constraint on schema generation.
    @Column(nullable = false)
    private String email;

    @JsonIgnore
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

    @Column(name = "invite_token", length = 64, unique = true)
    private String inviteToken;

    @Column(name = "invite_token_expires_at")
    private Instant inviteTokenExpiresAt;

    @JsonIgnore
    @Column(name = "apple_subject", length = 255)
    private String appleSubject;

    @JsonIgnore
    @Column(name = "apple_client_id", length = 255)
    private String appleClientId;

    /**
     * Apple refresh token from exchanging the sign-in authorization code. Held
     * for one reason: App Store Review Guideline 5.1.1(v) requires apps that
     * offer Sign in with Apple to REVOKE the user's tokens when they delete
     * their account. Null when the link predates code exchange or the Apple
     * key is not configured — sign-in itself never depends on it.
     */
    @JsonIgnore
    @Column(name = "apple_refresh_token", columnDefinition = "text")
    private String appleRefreshToken;

    // Optional override of tenantId from BaseTenantEntity
    // If a user is SUPER_ADMIN, tenantId might be null
}
