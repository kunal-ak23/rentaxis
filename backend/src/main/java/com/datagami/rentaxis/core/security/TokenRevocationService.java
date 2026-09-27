package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes bearer tokens revocable (security audit 2026-09-24, P1-2).
 *
 * <p>A token is only as good as the row behind it. On every bearer request
 * {@link ApiSecurityFilter} asks {@link #rejectionReason}; a token is refused
 * when its user is gone or not ACTIVE, when its {@code tv} claim is not the
 * user's current {@code users.token_version}, or when the organisation it is
 * about to act in is not ACTIVE.
 *
 * <p>The row lookups are cached for {@link #CACHE_TTL} so a burst of requests
 * costs one query, not one per request. Every revocation that goes through
 * {@link #revokeAllTokens} evicts the entry at once and again after commit (so a
 * request racing the transaction cannot re-cache the old version), so the cache
 * never delays a revocation made in this process. The TTL only bounds changes
 * written some other way: a status edited directly in the database, or another
 * backend instance's cache.
 *
 * <p>The same cached row also answers the legacy X-User-* path
 * ({@link LegacyHeaderIdentityCheck}, break round 1 F1/F2): the user's current
 * role, home tenant and memberships. So every in-process change to any of those
 * must evict the user here — {@code UserService} does on update, delete,
 * membership add/remove, and {@code LandlordOrgService.deleteTenant} evicts
 * everyone it reparents.
 */
@Service
public class TokenRevocationService implements BearerTokenStateCheck, LegacyHeaderIdentityCheck {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private record UserState(int tokenVersion, String status, UserRole role, UUID homeTenantId,
            Set<UUID> memberTenantIds) {
    }

    private final UserRepository userRepository;
    private final LandlordOrgRepository orgRepository;

    // Only users that exist. A lookup that found nobody goes in its own small,
    // short-lived cache below: any caller can send X-User-Id values at random,
    // and if those empties shared this cache they could evict every real user's
    // entry (break round 1 review) and turn each request into a query.
    private final Cache<UUID, UserState> users = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL).maximumSize(50_000).build();
    static final Duration MISSING_TTL = Duration.ofSeconds(5);
    private final Cache<UUID, Boolean> missingUsers = Caffeine.newBuilder()
            .expireAfterWrite(MISSING_TTL).maximumSize(1_000).build();
    private final Cache<UUID, Optional<String>> orgStatuses = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL).maximumSize(10_000).build();

    public TokenRevocationService(UserRepository userRepository, LandlordOrgRepository orgRepository) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
    }

    private Optional<UserState> state(UUID userId) {
        UserState cached = users.getIfPresent(userId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (missingUsers.getIfPresent(userId) != null) {
            return Optional.empty();
        }
        Optional<UserState> loaded = userRepository.findAuthStateById(userId).map(TokenRevocationService::toState);
        if (loaded.isPresent()) {
            users.put(userId, loaded.get());
        } else {
            missingUsers.put(userId, Boolean.TRUE);
        }
        return loaded;
    }

    /** How many existing users are cached (tests: unknown ids must never land here). */
    long cachedUserCount() {
        users.cleanUp();
        return users.estimatedSize();
    }

    private static UserState toState(UserRepository.AuthState s) {
        UserRole role;
        try {
            role = s.getRole() == null ? null : UserRole.valueOf(s.getRole());
        } catch (IllegalArgumentException unknownRole) {
            role = null;
        }
        Set<UUID> members = new LinkedHashSet<>();
        if (s.getMemberTenantIds() != null && !s.getMemberTenantIds().isBlank()) {
            for (String id : s.getMemberTenantIds().split(",")) {
                members.add(UUID.fromString(id.trim()));
            }
        }
        return new UserState(s.getTokenVersion() == null ? 0 : s.getTokenVersion(), s.getStatus(), role,
                s.getTenantId() == null ? null : UUID.fromString(s.getTenantId()),
                Collections.unmodifiableSet(members));
    }

    @Override
    public Optional<CurrentUser> currentUser(UUID userId) {
        return state(userId).map(s -> new CurrentUser(s.role(), UserStatus.ACTIVE.name().equals(s.status()),
                s.homeTenantId(), s.memberTenantIds()));
    }

    @Override
    public boolean orgActive(UUID tenantId) {
        Optional<String> orgStatus = orgStatuses.get(tenantId, orgRepository::findStatusById);
        return orgStatus.isPresent() && "ACTIVE".equalsIgnoreCase(orgStatus.get());
    }

    @Override
    public String rejectionReason(AuthTokenService.VerifiedIdentity identity, UUID activeTenantId) {
        Optional<UserState> state = state(identity.userId());
        if (state.isEmpty()) {
            return "user no longer exists";
        }
        if (!UserStatus.ACTIVE.name().equals(state.get().status())) {
            return "user is not active";
        }
        if (state.get().tokenVersion() != identity.tokenVersion()) {
            return "token has been revoked";
        }
        // SUPER_ADMIN is exempt: it is the role that re-activates an organisation.
        if (activeTenantId != null && identity.role() != UserRole.SUPER_ADMIN && !orgActive(activeTenantId)) {
            return "organisation is not active";
        }
        return null;
    }

    /**
     * Invalidates every bearer token issued to the user so far. Call it inside
     * the transaction that makes the change (password, role, tenant, membership,
     * deletion): the increment commits or rolls back with it.
     */
    public void revokeAllTokens(UUID userId) {
        userRepository.bumpTokenVersion(userId);
        evictUserAfterCommit(userId);
    }

    /** Drops the cached row state, now and again once the current transaction commits. */
    public void evictUserAfterCommit(UUID userId) {
        users.invalidate(userId);
        missingUsers.invalidate(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    users.invalidate(userId);
                    missingUsers.invalidate(userId);
                }
            });
        }
    }

    /**
     * Drops every cached user, now and after commit. For bulk writes that change
     * users without naming them ({@code LandlordOrgService.deleteTenant}
     * reparents every cross-tenant member of the deleted organisation).
     */
    public void evictAllUsersAfterCommit() {
        users.invalidateAll();
        missingUsers.invalidateAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    users.invalidateAll();
                    missingUsers.invalidateAll();
                }
            });
        }
    }

    /** An organisation's status changed: stop serving the cached one. */
    public void evictOrg(UUID tenantId) {
        orgStatuses.invalidate(tenantId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    orgStatuses.invalidate(tenantId);
                }
            });
        }
    }

    /** The user's current token version, read past the cache (for minting a replacement token). */
    public int currentTokenVersion(UUID userId) {
        return userRepository.findTokenStateById(userId)
                .map(s -> s.getTokenVersion() == null ? 0 : s.getTokenVersion())
                .orElse(0);
    }
}
