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
import java.util.Optional;
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
 */
@Service
public class TokenRevocationService implements BearerTokenStateCheck {

    static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private record UserState(int tokenVersion, String status) {
    }

    private final UserRepository userRepository;
    private final LandlordOrgRepository orgRepository;

    // Optional.empty() is cached too: a deleted user stays refused without a
    // query per request.
    private final Cache<UUID, Optional<UserState>> users = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL).maximumSize(50_000).build();
    private final Cache<UUID, Optional<String>> orgStatuses = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL).maximumSize(10_000).build();

    public TokenRevocationService(UserRepository userRepository, LandlordOrgRepository orgRepository) {
        this.userRepository = userRepository;
        this.orgRepository = orgRepository;
    }

    @Override
    public String rejectionReason(AuthTokenService.VerifiedIdentity identity, UUID activeTenantId) {
        Optional<UserState> state = users.get(identity.userId(), id -> userRepository.findTokenStateById(id)
                .map(s -> new UserState(s.getTokenVersion() == null ? 0 : s.getTokenVersion(), s.getStatus())));
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
        if (activeTenantId != null && identity.role() != UserRole.SUPER_ADMIN) {
            Optional<String> orgStatus = orgStatuses.get(activeTenantId, orgRepository::findStatusById);
            if (orgStatus.isEmpty() || !"ACTIVE".equalsIgnoreCase(orgStatus.get())) {
                return "organisation is not active";
            }
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    users.invalidate(userId);
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
