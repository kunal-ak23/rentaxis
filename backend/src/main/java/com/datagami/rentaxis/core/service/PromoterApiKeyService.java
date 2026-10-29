package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.FiguresChangedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.ApiKeySecrets;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoterApiKey;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.PromoterApiKeyRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Promoter developer API keys: issue, rotate, revoke, authenticate, and the
 * super-admin listing.
 *
 * <p>Promoter-side methods act for the promoter in its own organisation (a promoter
 * belongs to exactly one, {@code User.tenantId}); they run in a transaction so the
 * tenant filter applies, and every key lookup also names the tenant and the promoter.
 * Someone else's key is a 404, never a 403, so ids are not an oracle.
 *
 * <p>{@link #authenticate}, {@link #listAll} and {@link #revokeByAdmin} are
 * cross-tenant and need no tenant context: they read keys with native SQL, which the
 * tenant filter does not touch.
 */
@Service
@Slf4j
public class PromoterApiKeyService {

    public static final int MAX_ACTIVE_KEYS = 2;
    public static final int MAX_NAME = 80;
    public static final int MAX_REASON = 500;
    public static final Duration ROTATION_GRACE = Duration.ofHours(24);
    public static final Duration LAST_USED_GRANULARITY = Duration.ofSeconds(60);
    public static final String LIMIT_CODE = "api_key.limit";
    public static final String NOT_ACTIVE_CODE = "api_key.not_active";
    private static final Set<String> STATUSES = Set.of(PromoterApiKey.ACTIVE, PromoterApiKey.EXPIRING,
            PromoterApiKey.REVOKED, PromoterApiKey.EXPIRED);

    public record IssuedKey(PromoterApiKey key, String secret) {
    }

    public record AuthenticatedKey(UUID keyId, UUID tenantId, UUID promoterUserId) {
    }

    public record AdminKeyRow(UUID id, UUID tenantId, String orgName, UUID promoterUserId, String promoterName,
                              String promoterEmail, String name, String keyPrefix, Instant createdAt,
                              Instant lastUsedAt, Instant expiresAt, Instant revokedAt, String revokedReason,
                              String status) {
    }

    private final PromoterApiKeyRepository keyRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public PromoterApiKeyService(PromoterApiKeyRepository keyRepository, UserRepository userRepository, Clock clock) {
        this.keyRepository = keyRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------ promoter side

    /** The promoter's own keys, newest first, every status. */
    @Transactional(readOnly = true)
    public List<PromoterApiKey> listForPromoter(UUID userId) {
        User promoter = requirePromoter(userId);
        return keyRepository.findForPromoter(promoter.getTenantId(), promoter.getId());
    }

    /** A new key; the secret is in the result and nowhere else. 409 {@code api_key.limit} at 2 active keys. */
    @Transactional
    public IssuedKey create(UUID promoterUserId, String name) {
        User promoter = requirePromoter(promoterUserId);
        String label = requireName(name);
        lockPromoter(promoter.getId());
        if (keyRepository.countActive(promoter.getTenantId(), promoter.getId()) >= MAX_ACTIVE_KEYS) {
            throw new FiguresChangedException(LIMIT_CODE,
                    "You already have " + MAX_ACTIVE_KEYS + " active keys. Revoke or rotate one first.");
        }
        IssuedKey issued = issue(promoter, label);
        log.info("promoter_api_key.created keyId={} tenantId={} userId={} prefix={}", issued.key().getId(),
                promoter.getTenantId(), promoter.getId(), issued.key().getKeyPrefix());
        return issued;
    }

    /**
     * A new key with the same name; the old one keeps working for 24 hours. Allowed at
     * the cap, since the old key stops counting as active. 404 if not the promoter's
     * key; 409 {@code api_key.not_active} if it is revoked or already expiring.
     */
    @Transactional
    public IssuedKey rotate(UUID promoterUserId, UUID keyId) {
        User promoter = requirePromoter(promoterUserId);
        lockPromoter(promoter.getId());
        PromoterApiKey old = requireOwnedForUpdate(promoter, keyId);
        if (old.getRevokedAt() != null || old.getExpiresAt() != null) {
            throw new FiguresChangedException(NOT_ACTIVE_CODE, "Only an active key can be rotated.");
        }
        old.setExpiresAt(now().plus(ROTATION_GRACE));
        IssuedKey issued = issue(promoter, old.getName());
        log.info("promoter_api_key.rotated oldKeyId={} newKeyId={} tenantId={} userId={} oldExpiresAt={}",
                old.getId(), issued.key().getId(), promoter.getTenantId(), promoter.getId(), old.getExpiresAt());
        return issued;
    }

    /** Immediate and permanent; reason optional. Revoking a revoked key changes nothing. */
    @Transactional
    public PromoterApiKey revokeByPromoter(UUID promoterUserId, UUID keyId, String reason) {
        User promoter = requirePromoter(promoterUserId);
        String why = optionalReason(reason);
        PromoterApiKey key = requireOwnedForUpdate(promoter, keyId);
        return revoke(key, promoter.getId(), why, "promoter");
    }

    // ------------------------------------------------------------ cross-tenant

    /** Super-admin kill switch for any organisation's key; a non-blank reason is required. */
    @Transactional
    public PromoterApiKey revokeByAdmin(UUID adminUserId, UUID keyId, String reason) {
        String why = optionalReason(reason);
        if (why == null) {
            throw new BusinessRuleViolationException("Give a reason for revoking this key.");
        }
        PromoterApiKey key = keyRepository.findAnyByIdForUpdate(keyId)
                .orElseThrow(() -> new NotFoundException("API key not found"));
        return revoke(key, adminUserId, why, "admin");
    }

    /**
     * The key behind {@code secret}, if it may be used right now: not revoked, not past
     * {@code expires_at}, and its user still an ACTIVE PROMOTER of the key's organisation
     * (checked on every call, nothing cached). Touches {@code last_used_at} at most once
     * a minute. Every failure is the same empty result.
     */
    @Transactional
    public Optional<AuthenticatedKey> authenticate(String secret) {
        if (!ApiKeySecrets.looksLikeKey(secret)) {
            return Optional.empty();
        }
        String hash = ApiKeySecrets.hash(secret);
        Optional<PromoterApiKey> found = keyRepository.findAnyByKeyHash(hash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PromoterApiKey key = found.get();
        if (!MessageDigest.isEqual(key.getKeyHash().getBytes(StandardCharsets.US_ASCII),
                hash.getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        Instant now = now();
        if (key.getRevokedAt() != null || (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now))) {
            return Optional.empty();
        }
        boolean promoterStillValid = userRepository.findById(key.getUserId())
                .filter(u -> u.getRole() == UserRole.PROMOTER)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .filter(u -> Objects.equals(u.getTenantId(), key.getTenantId()))
                .isPresent();
        if (!promoterStillValid) {
            return Optional.empty();
        }
        keyRepository.touchLastUsed(key.getId(), now, now.minus(LAST_USED_GRANULARITY));
        return Optional.of(new AuthenticatedKey(key.getId(), key.getTenantId(), key.getUserId()));
    }

    /**
     * Every organisation's keys, newest first. {@code q} matches org name, promoter
     * name or email, key name or prefix (case-insensitive substring); {@code status}
     * is one of active / expiring / revoked / expired, or null for all (else 400).
     */
    @Transactional(readOnly = true)
    public Page<AdminKeyRow> listAll(String q, String status, Pageable p) {
        String wanted = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (wanted != null && !STATUSES.contains(wanted)) {
            throw new BusinessRuleViolationException("Unknown status: " + status);
        }
        String pattern = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        Instant now = now();
        // The order is fixed in the query; a caller's sort would name entity fields the native SQL lacks.
        Pageable page = PageRequest.of(p.getPageNumber(), p.getPageSize());
        return keyRepository.findAllForAdmin(pattern, wanted, now, page)
                .map(r -> new AdminKeyRow(r.getId(), r.getTenantId(), r.getOrgName(), r.getPromoterUserId(),
                        r.getPromoterName(), r.getPromoterEmail(), r.getName(), r.getKeyPrefix(), r.getCreatedAt(),
                        r.getLastUsedAt(), r.getExpiresAt(), r.getRevokedAt(), r.getRevokedReason(),
                        PromoterApiKey.statusAt(now, r.getRevokedAt(), r.getExpiresAt())));
    }

    // ------------------------------------------------------------ helpers

    /**
     * The caller as a PROMOTER. A request's tenant context, when set, must be the
     * promoter's own organisation (the tenant filter would hide the user otherwise).
     */
    private User requirePromoter(UUID userId) {
        User user = userId == null ? null : userRepository.findById(userId)
                .filter(u -> u.getRole() == UserRole.PROMOTER)
                .filter(u -> u.getTenantId() != null)
                .orElse(null);
        UUID context = TenantContextHolder.getTenantId();
        if (user == null || (context != null && !context.equals(user.getTenantId()))) {
            throw new AccessDeniedException("Not a promoter of this organisation.");
        }
        return user;
    }

    /** Locked, and the promoter's own (else 404). */
    private PromoterApiKey requireOwnedForUpdate(User promoter, UUID keyId) {
        return Optional.ofNullable(keyId)
                .flatMap(keyRepository::findAnyByIdForUpdate)
                .filter(k -> Objects.equals(k.getTenantId(), promoter.getTenantId()))
                .filter(k -> Objects.equals(k.getUserId(), promoter.getId()))
                .orElseThrow(() -> new NotFoundException("API key not found"));
    }

    /** Serialises one promoter's create/rotate so two at once cannot both pass the cap. */
    private void lockPromoter(UUID userId) {
        entityManager.createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", "promoter_api_key:" + userId)
                .getSingleResult();
    }

    private IssuedKey issue(User promoter, String name) {
        String secret = ApiKeySecrets.generate();
        PromoterApiKey key = new PromoterApiKey();
        key.setTenantId(promoter.getTenantId());
        key.setUserId(promoter.getId());
        key.setName(name);
        key.setKeyPrefix(ApiKeySecrets.prefix(secret));
        key.setKeyHash(ApiKeySecrets.hash(secret));
        key.setCreatedAt(now());
        key.setCreatedBy(promoter.getId());
        return new IssuedKey(keyRepository.save(key), secret);
    }

    private PromoterApiKey revoke(PromoterApiKey key, UUID by, String reason, String actor) {
        if (key.getRevokedAt() != null) {
            return key;
        }
        key.setRevokedAt(now());
        key.setRevokedBy(by);
        key.setRevokedReason(reason);
        log.info("promoter_api_key.revoked keyId={} tenantId={} userId={} by={} actor={}", key.getId(),
                key.getTenantId(), key.getUserId(), by, actor);
        return key;
    }

    private static String requireName(String name) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > MAX_NAME) {
            throw new BusinessRuleViolationException("A key name must be 1 to " + MAX_NAME + " characters.");
        }
        return label;
    }

    private static String optionalReason(String reason) {
        String why = reason == null ? null : reason.trim();
        if (why == null || why.isEmpty()) {
            return null;
        }
        if (why.length() > MAX_REASON) {
            throw new BusinessRuleViolationException("The reason must be at most " + MAX_REASON + " characters.");
        }
        return why;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** At the database's precision (microseconds), like {@code PromoterService.now()}. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
