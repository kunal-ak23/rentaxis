package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.security.AuthTokenService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoterAccess;
import com.datagami.rentaxis.domain.entity.PromoterBusiness;
import com.datagami.rentaxis.domain.entity.PromoterProperty;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PromoAdPropertyRepository;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterAccessRepository;
import com.datagami.rentaxis.domain.repository.PromoterBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterPropertyRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.CurrentLeaseFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

/**
 * Shared fixtures for the promoter-role ITs: real organisations, users, properties,
 * renters on current leases, promo businesses and ads, and an HTTP client that
 * authenticates the way the web proxy does (legacy X-User-* headers, judged against
 * the stored row) or with a bearer token.
 *
 * <p>Same token secret as {@code AbstractCallerIdentityIT}, so these classes share
 * that cached application context instead of building another one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.token-secret=ticket-caller-identity-it-secret-32-bytes-plus")
abstract class AbstractPromoterIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired AuthTokenService tokens;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PromoBusinessRepository businessRepo;
    @Autowired PromoAdRepository adRepo;
    @Autowired PromoAdPropertyRepository adPropertyRepo;
    @Autowired PromoterAccessRepository accessRepo;
    @Autowired PromoterBusinessRepository promoterBusinessRepo;
    @Autowired PromoterPropertyRepository promoterPropertyRepo;

    @AfterEach
    void clearContexts() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    record Resp(int status, JsonNode body) {
        String text(String field) {
            JsonNode n = body == null ? null : body.get(field);
            return n == null || n.isNull() ? null : n.asText();
        }

        List<String> texts(String field) {
            JsonNode n = body.get(field);
            return StreamSupport.stream(n.spliterator(), false).map(JsonNode::asText).toList();
        }
    }

    // ------------------------------------------------------------- fixtures

    UUID org() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Promoter IT " + UUID.randomUUID());
        return orgRepo.save(org).getId();
    }

    User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.test");
        u.setName(role.name() + " " + UUID.randomUUID().toString().substring(0, 6));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        return userRepo.save(u);
    }

    Property property(UUID tenantId, String name) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            Property p = new Property();
            p.setNameEn(name + " " + UUID.randomUUID().toString().substring(0, 6));
            p.setNameAr("عقار " + name);
            p.setEmirate(Emirate.DUBAI);
            return propertyRepo.save(p);
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** A RENTER login with a current lease at {@code property}. */
    User renterAt(UUID tenantId, Property property) {
        User u = user(tenantId, UserRole.RENTER);
        TenantContextHolder.setTenantId(tenantId);
        try {
            Renter r = new Renter();
            r.setNameEn("Renter " + u.getId());
            r.setUserId(u.getId());
            CurrentLeaseFixture.currentLease(unitRepo, leaseRepo, tenantId, property, renterRepo.save(r));
        } finally {
            TenantContextHolder.clear();
        }
        return u;
    }

    PromoBusiness business(UUID tenantId, String name) {
        PromoBusiness b = new PromoBusiness();
        b.setTenantId(tenantId);
        b.setNameEn(name + " " + UUID.randomUUID().toString().substring(0, 6));
        b.setPhoneE164("+971501234567");
        return businessRepo.save(b);
    }

    /** A PROMOTER of {@code tenantId}, its access row and grants, written directly. */
    User promoter(UUID tenantId, boolean canCreateBusinesses, List<UUID> businessIds, List<UUID> propertyIds) {
        User p = user(tenantId, UserRole.PROMOTER);
        TenantContextHolder.setTenantId(tenantId);
        try {
            PromoterAccess access = new PromoterAccess();
            access.setTenantId(tenantId);
            access.setUserId(p.getId());
            access.setCanCreateBusinesses(canCreateBusinesses);
            accessRepo.save(access);
            for (UUID b : businessIds) {
                promoterBusinessRepo.save(PromoterBusiness.of(tenantId, p.getId(), b));
            }
            for (UUID prop : propertyIds) {
                promoterPropertyRepo.save(PromoterProperty.of(tenantId, p.getId(), prop));
            }
        } finally {
            TenantContextHolder.clear();
        }
        return p;
    }

    /** An ad written directly: its approval state, who wrote it (null = admin) and its targeting. */
    PromoAd ad(UUID tenantId, UUID businessId, PromoApprovalStatus status, UUID promoterUserId, UUID... propertyIds) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            PromoAd a = new PromoAd();
            a.setTenantId(tenantId);
            a.setBusinessId(businessId);
            a.setTitleEn("Offer " + UUID.randomUUID().toString().substring(0, 6));
            a.setApprovalStatus(status);
            a.setPromoterUserId(promoterUserId);
            PromoAd saved = adRepo.save(a);
            for (UUID p : propertyIds) {
                PromoAdProperty row = new PromoAdProperty();
                row.setTenantId(tenantId);
                row.setAdId(saved.getId());
                row.setPropertyId(p);
                adPropertyRepo.save(row);
            }
            return saved;
        } finally {
            TenantContextHolder.clear();
        }
    }

    /** The ad ids the renter's offers screen lists (every placement). */
    List<String> offerIds(User renter) {
        Resp res = get(renter, "/api/v1/promotions/offers");
        if (res.status() != 200) {
            throw new AssertionError("offers answered " + res.status() + ": " + res.body());
        }
        // Top-level ids only: each card also nests its business, which has an id too.
        return StreamSupport.stream(res.body().spliterator(), false).map(card -> card.get("id").asText()).toList();
    }

    /**
     * Review round 1, I2: an approval names the submission the admin looked at. The
     * ad's current {@code submitted_at}, or a fixed instant for an ad that has none
     * (or does not exist), so the other refusals still get their turn.
     */
    String submittedAt(Object adId) {
        List<java.sql.Timestamp> at = jdbc.queryForList(
                "SELECT submitted_at FROM promo_ads WHERE id = ?::uuid", java.sql.Timestamp.class, adId.toString());
        return at.isEmpty() || at.get(0) == null ? "2026-01-01T00:00:00Z" : at.get(0).toInstant().toString();
    }

    Resp approve(User admin, Object adId) {
        return post(admin, "/api/v1/promotions/ads/" + adId + "/approve", body("expectedSubmittedAt", submittedAt(adId)));
    }

    Resp reject(User admin, Object adId, String reason) {
        return post(admin, "/api/v1/promotions/ads/" + adId + "/reject",
                body("reason", reason, "expectedSubmittedAt", submittedAt(adId)));
    }

    PromoAd reload(UUID adId) {
        return adRepo.findById(adId).orElseThrow();
    }

    // ------------------------------------------------------------------ HTTP

    RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** As the web proxy calls: legacy identity headers, judged against the stored row. */
    Resp call(User caller, HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec spec = client().method(method).uri(path)
                .header("X-User-Id", caller.getId().toString())
                .header("X-User-Role", caller.getRole().name());
        if (caller.getTenantId() != null) {
            spec = spec.header("X-Tenant-Id", caller.getTenantId().toString())
                    .header("X-User-Tenant-Id", caller.getTenantId().toString());
        }
        return send(spec, body);
    }

    /** With the caller's own bearer token, as the mobile apps call. */
    Resp bearer(User caller, HttpMethod method, String path, Object body) {
        String token = tokens.issue(caller.getId(), caller.getRole(), caller.getTenantId(),
                caller.getTenantId() == null ? List.of() : List.of(caller.getTenantId()));
        RestClient.RequestBodySpec spec = client().method(method).uri(path)
                .header("Authorization", "Bearer " + token);
        return send(spec, body);
    }

    Resp get(User caller, String path) {
        return call(caller, HttpMethod.GET, path, null);
    }

    Resp post(User caller, String path, Object body) {
        return call(caller, HttpMethod.POST, path, body);
    }

    Resp put(User caller, String path, Object body) {
        return call(caller, HttpMethod.PUT, path, body);
    }

    Resp delete(User caller, String path) {
        return call(caller, HttpMethod.DELETE, path, null);
    }

    private Resp send(RestClient.RequestBodySpec spec, Object body) {
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON);
            spec.body(body);
        }
        ResponseEntity<byte[]> res = spec.retrieve().onStatus(s -> true, (req, r) -> { }).toEntity(byte[].class);
        JsonNode node = null;
        byte[] bytes = res.getBody();
        if (bytes != null && bytes.length > 0) {
            try {
                node = json.readTree(bytes);
            } catch (Exception notJson) {
                node = null;
            }
        }
        return new Resp(res.getStatusCode().value(), node);
    }

    /** A JSON body with nulls allowed (Map.of refuses them). */
    static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static List<String> ids(UUID... ids) {
        return Arrays.stream(ids).map(UUID::toString).toList();
    }
}
