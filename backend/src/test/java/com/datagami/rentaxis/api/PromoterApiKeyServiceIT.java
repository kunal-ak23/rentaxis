package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.FiguresChangedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.ApiKeySecrets;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.core.service.PromoterApiKeyService.AdminKeyRow;
import com.datagami.rentaxis.core.service.PromoterApiKeyService.AuthenticatedKey;
import com.datagami.rentaxis.core.service.PromoterApiKeyService.IssuedKey;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PromoterApiKey;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Promoter developer API, task 1: key storage and lifecycle. Promoter-side calls run
 * with the promoter's organisation in tenant context, as a signed-in request would;
 * the cross-tenant ones (authenticate, listAll, revokeByAdmin) with none.
 */
class PromoterApiKeyServiceIT extends AbstractPromoterIT {

    @Autowired PromoterApiKeyService keys;

    private User newPromoter() {
        return promoter(org(), false, List.of(), List.of());
    }

    private <T> T as(User promoter, java.util.function.Supplier<T> call) {
        TenantContextHolder.setTenantId(promoter.getTenantId());
        try {
            return call.get();
        } finally {
            TenantContextHolder.clear();
        }
    }

    private IssuedKey create(User promoter, String name) {
        return as(promoter, () -> keys.create(promoter.getId(), name));
    }

    private Optional<AuthenticatedKey> authenticate(String secret) {
        TenantContextHolder.clear();
        return keys.authenticate(secret);
    }

    private Instant column(UUID keyId, String column) {
        Timestamp t = jdbc.queryForObject("SELECT " + column + " FROM promoter_api_key WHERE id = ?",
                Timestamp.class, keyId);
        return t == null ? null : t.toInstant();
    }

    @Test
    void createReturnsSecretOnceAndStoresOnlyHash() {
        User p = newPromoter();

        IssuedKey issued = create(p, "  Production  ");

        assertThat(issued.secret()).startsWith("mfk_live_");
        assertThat(issued.key().getName()).isEqualTo("Production");
        assertThat(issued.key().getKeyPrefix()).isEqualTo(issued.secret().substring(0, 12));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM promoter_api_key WHERE id = ?", issued.key().getId());
        assertThat(row.get("key_hash")).isEqualTo(ApiKeySecrets.hash(issued.secret()));
        assertThat(row.get("tenant_id")).isEqualTo(p.getTenantId());
        assertThat(row.get("user_id")).isEqualTo(p.getId());
        assertThat(row.get("created_by")).isEqualTo(p.getId());
        assertThat(row.values()).noneMatch(v -> v != null && v.toString().contains(issued.secret()));

        List<PromoterApiKey> listed = as(p, () -> keys.listForPromoter(p.getId()));
        assertThat(listed).extracting(PromoterApiKey::getId).containsExactly(issued.key().getId());

        Optional<AuthenticatedKey> auth = authenticate(issued.secret());
        assertThat(auth).contains(new AuthenticatedKey(issued.key().getId(), p.getTenantId(), p.getId()));
    }

    @Test
    void createRejectsBlankOrLongNames() {
        User p = newPromoter();
        assertThatThrownBy(() -> create(p, "   ")).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> create(p, null)).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> create(p, "x".repeat(81))).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(create(p, "x".repeat(80)).key().getName()).hasSize(80);
    }

    @Test
    void listIsNewestFirstAndOnlyTheCallersKeys() {
        User p = newPromoter();
        User other = promoter(p.getTenantId(), false, List.of(), List.of());
        IssuedKey first = create(p, "first");
        jdbc.update("UPDATE promoter_api_key SET created_at = now() - interval '1 hour' WHERE id = ?",
                first.key().getId());
        IssuedKey second = create(p, "second");
        create(other, "theirs");

        assertThat(as(p, () -> keys.listForPromoter(p.getId())))
                .extracting(PromoterApiKey::getId).containsExactly(second.key().getId(), first.key().getId());
    }

    @Test
    void thirdActiveKeyIs409() {
        User p = newPromoter();
        create(p, "one");
        create(p, "two");

        assertThatThrownBy(() -> create(p, "three"))
                .isInstanceOfSatisfying(FiguresChangedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("api_key.limit"));
    }

    @Test
    void revokedKeysFreeASlot() {
        User p = newPromoter();
        IssuedKey one = create(p, "one");
        create(p, "two");
        as(p, () -> keys.revokeByPromoter(p.getId(), one.key().getId(), null));

        assertThat(create(p, "three").secret()).isNotBlank();
    }

    @Test
    void rotateIssuesNewAndExpiresOldIn24h() {
        User p = newPromoter();
        IssuedKey old = create(p, "prod");

        IssuedKey fresh = as(p, () -> keys.rotate(p.getId(), old.key().getId()));

        assertThat(fresh.key().getId()).isNotEqualTo(old.key().getId());
        assertThat(fresh.secret()).isNotEqualTo(old.secret());
        assertThat(fresh.key().getName()).isEqualTo("prod");
        assertThat(fresh.key().getExpiresAt()).isNull();
        assertThat(column(old.key().getId(), "expires_at"))
                .isCloseTo(Instant.now().plus(Duration.ofHours(24)), within(Duration.ofMinutes(1)));
        assertThat(column(old.key().getId(), "revoked_at")).isNull();
        assertThat(authenticate(old.secret())).isPresent();
        assertThat(authenticate(fresh.secret())).isPresent();
        assertThat(PromoterApiKey.statusAt(Instant.now(), null, column(old.key().getId(), "expires_at")))
                .isEqualTo("expiring");
    }

    @Test
    void rotateAtCapIsAllowed() {
        User p = newPromoter();
        IssuedKey one = create(p, "one");
        create(p, "two");

        IssuedKey fresh = as(p, () -> keys.rotate(p.getId(), one.key().getId()));

        assertThat(authenticate(fresh.secret())).isPresent();
        // Still two active (two + the new one); the rotated one is on its way out.
        assertThatThrownBy(() -> create(p, "three")).isInstanceOf(FiguresChangedException.class);
    }

    @Test
    void rotatingExpiringKeyIs409() {
        User p = newPromoter();
        IssuedKey old = create(p, "prod");
        as(p, () -> keys.rotate(p.getId(), old.key().getId()));

        assertThatThrownBy(() -> as(p, () -> keys.rotate(p.getId(), old.key().getId())))
                .isInstanceOfSatisfying(FiguresChangedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("api_key.not_active"));

        IssuedKey revoked = create(p, "gone");
        as(p, () -> keys.revokeByPromoter(p.getId(), revoked.key().getId(), "done"));
        assertThatThrownBy(() -> as(p, () -> keys.rotate(p.getId(), revoked.key().getId())))
                .isInstanceOfSatisfying(FiguresChangedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("api_key.not_active"));
    }

    @Test
    void revokedKeyDoesNotAuthenticate() {
        User p = newPromoter();
        IssuedKey k = create(p, "prod");

        PromoterApiKey revoked = as(p, () -> keys.revokeByPromoter(p.getId(), k.key().getId(), "  "));

        assertThat(revoked.getRevokedAt()).isNotNull();
        assertThat(revoked.getRevokedBy()).isEqualTo(p.getId());
        assertThat(revoked.getRevokedReason()).isNull();
        assertThat(authenticate(k.secret())).isEmpty();

        // Idempotent: a second revoke changes nothing.
        Instant at = column(k.key().getId(), "revoked_at");
        PromoterApiKey again = as(p, () -> keys.revokeByPromoter(p.getId(), k.key().getId(), "later"));
        assertThat(again.getRevokedReason()).isNull();
        assertThat(column(k.key().getId(), "revoked_at")).isEqualTo(at);
    }

    @Test
    void expiredKeyDoesNotAuthenticate() {
        User p = newPromoter();
        IssuedKey k = create(p, "prod");
        jdbc.update("UPDATE promoter_api_key SET expires_at = now() - interval '1 second' WHERE id = ?",
                k.key().getId());

        assertThat(authenticate(k.secret())).isEmpty();
    }

    @Test
    void keyDoesNotAuthenticateOnceItsPromoterIsNoLongerOne() {
        User p = newPromoter();
        IssuedKey k = create(p, "prod");
        assertThat(authenticate(k.secret())).isPresent();

        jdbc.update("UPDATE users SET status = 'INACTIVE' WHERE id = ?", p.getId());
        assertThat(authenticate(k.secret())).isEmpty();

        jdbc.update("UPDATE users SET status = 'ACTIVE', role = 'TENANT_USER' WHERE id = ?", p.getId());
        assertThat(authenticate(k.secret())).isEmpty();

        jdbc.update("UPDATE users SET role = 'PROMOTER', tenant_id = ? WHERE id = ?", org(), p.getId());
        assertThat(authenticate(k.secret())).isEmpty();
    }

    @Test
    void unknownOrMalformedSecretsDoNotAuthenticate() {
        assertThat(authenticate(null)).isEmpty();
        assertThat(authenticate("Bearer x")).isEmpty();
        assertThat(authenticate(ApiKeySecrets.generate())).isEmpty();
    }

    @Test
    void promoterCannotTouchAnotherPromotersKey() {
        User p = newPromoter();
        User sameOrg = promoter(p.getTenantId(), false, List.of(), List.of());
        User otherOrg = newPromoter();
        IssuedKey k = create(p, "prod");

        for (User intruder : List.of(sameOrg, otherOrg)) {
            assertThatThrownBy(() -> as(intruder, () -> keys.rotate(intruder.getId(), k.key().getId())))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> as(intruder, () -> keys.revokeByPromoter(intruder.getId(), k.key().getId(), null)))
                    .isInstanceOf(NotFoundException.class);
            assertThat(as(intruder, () -> keys.listForPromoter(intruder.getId()))).isEmpty();
        }
        assertThat(authenticate(k.secret())).isPresent();
        assertThat(column(k.key().getId(), "expires_at")).isNull();
    }

    @Test
    void adminRevokeNeedsReason() {
        User p = newPromoter();
        User admin = user(null, UserRole.SUPER_ADMIN);
        IssuedKey k = create(p, "prod");

        TenantContextHolder.clear();
        assertThatThrownBy(() -> keys.revokeByAdmin(admin.getId(), k.key().getId(), "  "))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> keys.revokeByAdmin(admin.getId(), k.key().getId(), null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> keys.revokeByAdmin(admin.getId(), UUID.randomUUID(), "leak"))
                .isInstanceOf(NotFoundException.class);
        assertThat(authenticate(k.secret())).isPresent();
    }

    @Test
    void adminRevokeRecordsWhoAndWhy() {
        User p = newPromoter();
        User admin = user(null, UserRole.SUPER_ADMIN);
        IssuedKey k = create(p, "prod");

        TenantContextHolder.clear();
        PromoterApiKey revoked = keys.revokeByAdmin(admin.getId(), k.key().getId(), "  Leaked on GitHub  ");

        assertThat(revoked.getRevokedBy()).isEqualTo(admin.getId());
        assertThat(revoked.getRevokedReason()).isEqualTo("Leaked on GitHub");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT revoked_by, revoked_reason, revoked_at FROM promoter_api_key WHERE id = ?", k.key().getId());
        assertThat(row.get("revoked_by")).isEqualTo(admin.getId());
        assertThat(row.get("revoked_reason")).isEqualTo("Leaked on GitHub");
        assertThat(row.get("revoked_at")).isNotNull();
        assertThat(authenticate(k.secret())).isEmpty();
        assertThat(as(p, () -> keys.listForPromoter(p.getId())).get(0).getRevokedReason())
                .isEqualTo("Leaked on GitHub");
    }

    @Test
    void listAllSpansOrgsAndFilters() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User a = newPromoter();
        User b = newPromoter();
        jdbc.update("UPDATE landlord_org SET name = ? WHERE id = ?", "Org A " + tag, a.getTenantId());
        jdbc.update("UPDATE landlord_org SET name = ? WHERE id = ?", "Org B " + tag, b.getTenantId());
        IssuedKey aKey = create(a, "alpha " + tag);
        IssuedKey bKey = create(b, "beta " + tag);
        IssuedKey bOld = create(b, "rotated " + tag);
        as(b, () -> keys.rotate(b.getId(), bOld.key().getId()));
        User admin = user(null, UserRole.SUPER_ADMIN);
        TenantContextHolder.clear();
        keys.revokeByAdmin(admin.getId(), bKey.key().getId(), "leak");

        Page<AdminKeyRow> all = keys.listAll(tag, null, PageRequest.of(0, 50));
        assertThat(all.getTotalElements()).isEqualTo(4);
        assertThat(all.getContent()).extracting(AdminKeyRow::tenantId).contains(a.getTenantId(), b.getTenantId());
        AdminKeyRow alpha = all.getContent().stream()
                .filter(r -> r.id().equals(aKey.key().getId())).findFirst().orElseThrow();
        assertThat(alpha.orgName()).isEqualTo("Org A " + tag);
        assertThat(alpha.promoterUserId()).isEqualTo(a.getId());
        assertThat(alpha.promoterName()).isEqualTo(a.getName());
        assertThat(alpha.promoterEmail()).isEqualTo(a.getEmail());
        assertThat(alpha.keyPrefix()).isEqualTo(aKey.secret().substring(0, 12));
        assertThat(alpha.status()).isEqualTo("active");

        assertThat(keys.listAll("org a " + tag.toUpperCase(), null, PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::id).containsExactly(aKey.key().getId());
        assertThat(keys.listAll(a.getEmail().toUpperCase(), null, PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::id).containsExactly(aKey.key().getId());
        assertThat(keys.listAll(aKey.secret().substring(0, 12), null, PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::id).containsExactly(aKey.key().getId());

        Page<AdminKeyRow> revoked = keys.listAll(tag, "revoked", PageRequest.of(0, 50));
        assertThat(revoked.getContent()).extracting(AdminKeyRow::id).containsExactly(bKey.key().getId());
        assertThat(revoked.getContent().get(0).revokedReason()).isEqualTo("leak");
        assertThat(keys.listAll(tag, "expiring", PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::id).containsExactly(bOld.key().getId());
        assertThat(keys.listAll(tag, "active", PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::status).containsOnly("active").hasSize(2);
        jdbc.update("UPDATE promoter_api_key SET expires_at = now() - interval '1 minute' WHERE id = ?",
                bOld.key().getId());
        assertThat(keys.listAll(tag, "expired", PageRequest.of(0, 50)).getContent())
                .extracting(AdminKeyRow::id).containsExactly(bOld.key().getId());

        assertThat(keys.listAll(tag, null, PageRequest.of(0, 3)).getContent()).hasSize(3);
        assertThatThrownBy(() -> keys.listAll(tag, "bogus", PageRequest.of(0, 50)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void lastUsedTouchedAtMostOncePerMinute() {
        User p = newPromoter();
        IssuedKey k = create(p, "prod");
        assertThat(column(k.key().getId(), "last_used_at")).isNull();

        authenticate(k.secret());
        Instant first = column(k.key().getId(), "last_used_at");
        assertThat(first).isNotNull();

        authenticate(k.secret());
        assertThat(column(k.key().getId(), "last_used_at")).isEqualTo(first);

        jdbc.update("UPDATE promoter_api_key SET last_used_at = now() - interval '61 seconds' WHERE id = ?",
                k.key().getId());
        Instant old = column(k.key().getId(), "last_used_at");
        authenticate(k.secret());
        assertThat(column(k.key().getId(), "last_used_at")).isAfter(old);
    }

    @Test
    void statusDerivation() {
        Instant now = Instant.now();
        assertThat(PromoterApiKey.statusAt(now, now.minusSeconds(5), null)).isEqualTo("revoked");
        assertThat(PromoterApiKey.statusAt(now, now.minusSeconds(5), now.plusSeconds(5))).isEqualTo("revoked");
        assertThat(PromoterApiKey.statusAt(now, null, now)).isEqualTo("expired");
        assertThat(PromoterApiKey.statusAt(now, null, now.plusSeconds(5))).isEqualTo("expiring");
        assertThat(PromoterApiKey.statusAt(now, null, null)).isEqualTo("active");
    }
}
