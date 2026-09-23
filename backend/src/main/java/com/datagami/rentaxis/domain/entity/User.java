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

    /**
     * Revocation counter for bearer tokens (security audit P1-2). Every token
     * carries the value it was minted with ({@code tv} claim) and
     * {@code ApiSecurityFilter} refuses one that no longer matches.
     *
     * <p>{@code updatable = false} on purpose: the only writer is
     * {@code UserRepository.bumpTokenVersion}, an atomic increment. Without it a
     * later flush of a User loaded before the bump (Hibernate updates every
     * column) would write the old value back and silently un-revoke the tokens.
     */
    @JsonIgnore
    @Column(name = "token_version", nullable = false, updatable = false)
    private int tokenVersion;

    /**
     * The set-password invite secret: whoever holds it can choose this account's
     * password. Never serialised (PR #342 review C2); no response may carry it.
     */
    @JsonIgnore
    @Column(name = "invite_token", length = 64, unique = true)
    private String inviteToken;

    @JsonIgnore
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

    /**
     * Whether this account is still waiting on its set-password invite: a token
     * is outstanding and the holder has never signed in (PR #342 review I1).
     *
     * <p>{@code welcomedAt} is set on first sign-in, so a user who got in with a
     * password they were given is activated even if a legacy token is still on
     * the row. Offering "Resend invite" to them would mail an unsolicited
     * set-password link to an account that already has one.
     */
    public boolean hasPendingInvite() {
        return inviteToken != null && welcomedAt == null;
    }

    /** Drops the invite secret: the account has a password its holder knows. */
    public void clearInvite() {
        this.inviteToken = null;
        this.inviteTokenExpiresAt = null;
    }

    // Optional override of tenantId from BaseTenantEntity
    // If a user is SUPER_ADMIN, tenantId might be null
}
