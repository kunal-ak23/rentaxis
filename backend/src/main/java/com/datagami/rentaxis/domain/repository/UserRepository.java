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

    Optional<User> findByAppleClientIdAndAppleSubject(String appleClientId, String appleSubject);

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

    @Modifying
    @Query("""
            UPDATE User u
            SET u.inviteToken = NULL,
                u.inviteTokenExpiresAt = NULL
            WHERE u.id = :id
              AND u.inviteToken IS NOT NULL
            """)
    int clearInviteToken(@Param("id") UUID id);

    List<User> findByTenantId(UUID tenantId);

    /**
     * What {@code ApiSecurityFilter} needs to decide whether a bearer token is
     * still good: the row's token version and status. Native SQL so the tenant
     * Hibernate filter never narrows it (a SUPER_ADMIN has no tenant, and the
     * check runs before any tenant context is set).
     */
    @Query(value = "SELECT token_version AS tokenVersion, status AS status FROM users WHERE id = :id",
            nativeQuery = true)
    Optional<TokenState> findTokenStateById(@Param("id") UUID id);

    interface TokenState {
        Integer getTokenVersion();

        String getStatus();
    }

    /**
     * Revokes every bearer token issued to this user so far. Atomic increment in
     * SQL (never read-modify-write through the entity; see
     * {@code User.tokenVersion}). Native so the tenant filter cannot turn it into
     * a silent no-op for a user outside the caller's tenant.
     */
    @org.springframework.transaction.annotation.Transactional
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE users SET token_version = token_version + 1 WHERE id = :id", nativeQuery = true)
    int bumpTokenVersion(@Param("id") UUID id);

    /**
     * Explicit tenant-scoped batch lookup by id, mirroring
     * {@code PropertyRepository.findByTenantIdAndIdIn}.
     *
     * <p>Exists so the gate-pass report can resolve the scanning guard's name for a
     * whole month of traffic in one query instead of one per scan row. Prefer this
     * over {@link #findAllById(Iterable)} on any tenant-scoped path: a foreign id
     * passed to that one is caught only by the {@code tenantFilter} aspect having
     * fired, whereas the scope is in this SQL either way.
     *
     * <p>Distinct from {@link #findDisplayNameById}, and the difference is the
     * point: that one deliberately bypasses the tenant filter with native SQL
     * because SUPER_ADMIN attribution needs a cross-tenant read. Nothing on the
     * report path does — a scan is always performed by a guard inside the tenant
     * that owns it — so the report uses the scoped query and a foreign
     * {@code scanned_by_user_id} resolves to no name at all rather than to a name
     * from another tenant.
     */
    List<User> findByTenantIdAndIdIn(UUID tenantId, java.util.Collection<UUID> ids);

    List<User> findByRole(UserRole role);

    /** A user as a staff candidate: identity, role and status, and nothing else. */
    interface StaffCandidate {
        UUID getId();
        String getName();
        String getRole();
        String getStatus();
    }

    /**
     * The user, when they belong to {@code tenantId} — home tenant, or a
     * {@code user_tenant_memberships} row — or are a SUPER_ADMIN (who belongs to
     * no tenant and may act in any). Role and status are left to the caller to
     * judge, so "not one of ours" (empty) stays distinct from "one of ours who
     * cannot take this".
     *
     * <p>Native on purpose: the tenant filter on {@code users} hides a
     * multi-tenant admin whose home tenant is another organisation, which is
     * exactly the user this has to find. The tenant is the explicit argument.</p>
     */
    @Query(value = """
            SELECT u.id AS id, u.name AS name, u.role AS role, u.status AS status
            FROM users u
            WHERE u.id = :userId
              AND (u.tenant_id = :tenantId
                   OR u.role = 'SUPER_ADMIN'
                   OR EXISTS (SELECT 1 FROM user_tenant_memberships m
                              WHERE m.user_id = u.id AND m.tenant_id = :tenantId))
            """, nativeQuery = true)
    Optional<StaffCandidate> findStaffCandidateInTenant(@Param("userId") UUID userId,
                                                        @Param("tenantId") UUID tenantId);

    /**
     * ACTIVE users of {@code role} who belong to {@code tenantId} (home tenant or
     * membership), oldest account first, id as the tie-break — a deterministic
     * answer to "who is the default". Native for the reason above.
     */
    @Query(value = """
            SELECT u.id FROM users u
            WHERE u.status = 'ACTIVE' AND u.role = :role
              AND (u.tenant_id = :tenantId
                   OR EXISTS (SELECT 1 FROM user_tenant_memberships m
                              WHERE m.user_id = u.id AND m.tenant_id = :tenantId))
            ORDER BY u.created_at ASC NULLS LAST, u.id ASC
            """, nativeQuery = true)
    List<UUID> findActiveIdsInTenantByRoleOldestFirst(@Param("tenantId") UUID tenantId,
                                                      @Param("role") String role);

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
