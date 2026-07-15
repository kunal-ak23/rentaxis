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
    /**
     * Returns ALL users sharing this email. Post-migration 59, the same email
     * may exist as multiple User rows (one per tenant). Callers must handle
     * the multi-match case explicitly — usually by tie-breaking on password
     * (login flow) or by scoping to a tenant (creation flow).
     */
    List<User> findAllByEmail(String email);

    /**
     * Display-name lookup that bypasses the tenant Hibernate filter (native
     * SQL). Needed for audit/comment attribution: a SUPER_ADMIN acting inside
     * a tenant has {@code tenant_id = NULL}, so the filtered
     * {@link #findById} can't see them and names degrade to "Unknown".
     * Exposes nothing but the name — safe across tenants.
     */
    @Query(value = "SELECT name FROM users WHERE id = :id", nativeQuery = true)
    Optional<String> findDisplayNameById(@Param("id") UUID id);

    /**
     * Per-tenant email lookup. Returns the single user matching (tenantId, email)
     * if any. Use this in tenanted flows where you have tenant context.
     */
    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    /**
     * Lookup for SUPER_ADMIN identity (tenant_id IS NULL). Email is globally
     * unique within this scope per migration 59's partial index.
     */
    Optional<User> findByEmailAndTenantIdIsNull(String email);

    boolean existsByTenantIdAndEmail(UUID tenantId, String email);

    boolean existsByEmailAndTenantIdIsNull(String email);

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

    /**
     * Cross-tenant phone + role lookup, used by the pre-auth guard OTP login
     * where the phone number is the only identifier available and there is no
     * tenant context yet.
     *
     * <p>Returns a list because phone numbers carry no uniqueness constraint:
     * the same number may exist on rows in different tenants. Callers must
     * handle the multi-match case explicitly rather than assuming one row —
     * same contract as {@link #findAllByEmail}.
     */
    List<User> findByPhoneNumberAndRole(String phoneNumber, UserRole role);

    /**
     * Pre-check for {@code uq_users_guard_phone} (changeset 65).
     *
     * <p><b>Deliberately not tenant-scoped.</b> That index is
     * {@code ON users(phone_number) WHERE role='SECURITY_GUARD'} with no tenant
     * predicate, so a guard phone is unique <i>globally</i>, not per tenant. A
     * tenant-scoped pre-check would pass and then let the INSERT fail anyway,
     * which is the whole failure mode this exists to give a decent message for.
     * Match the index or do not bother.
     *
     * <p><b>This cannot be the guarantee, only the message.</b> Two things get
     * past it, so {@code UserService} must still translate the violation the
     * index throws:
     * <ul>
     *   <li>It is read-then-write, so two concurrent creates both see false.</li>
     *   <li>{@link User} extends {@code BaseTenantEntity}, so this derived query
     *       is subject to {@code tenantFilter} whenever {@code TenantAspect} has
     *       enabled it — while the index is global. A TENANT_ADMIN creating a
     *       guard whose number is already a guard's in another tenant thus sees
     *       false here and fails at the INSERT. That is ordinary production
     *       behaviour, not a race.</li>
     * </ul>
     */
    boolean existsByPhoneNumberAndRole(String phoneNumber, UserRole role);

    /**
     * As {@link #existsByPhoneNumberAndRole}, excluding one row — the update-path
     * variant, so a guard keeping their own number is not a conflict with
     * themselves.
     */
    boolean existsByPhoneNumberAndRoleAndIdNot(String phoneNumber, UserRole role, UUID id);
}
