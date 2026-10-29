package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter developer API, task 3: the unit count and paged units feed. Tenant
 * isolation is the point: only units of properties the key's promoter is granted,
 * in the key's organisation, ever appear.
 */
class PartnerUnitFeedIT extends AbstractPromoterIT {

    static final String COUNT = "/api/v1/partner/promoter/units/count";
    static final String UNITS = "/api/v1/partner/promoter/units";
    static final String EMPTY_FP = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Autowired PromoterApiKeyService keys;

    UUID orgA;

    @BeforeEach
    void setUp() {
        orgA = org();
    }

    // ---------------------------------------------------------------- helpers

    Unit unit(UUID tenantId, Property p, String number) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            Unit u = new Unit();
            u.setProperty(p);
            u.setUnitNumber(number);
            u.setTenantId(tenantId);
            return unitRepo.save(u);
        } finally {
            TenantContextHolder.clear();
        }
    }

    void units(UUID tenantId, Property p, int n) {
        for (int i = 1; i <= n; i++) {
            unit(tenantId, p, "U-" + i);
        }
    }

    String keyFor(User promoter) {
        return keys.create(promoter.getId(), "Feed").secret();
    }

    PartnerApiKeyAuthIT.PartnerResp get(String secret, String path) {
        return PartnerApiKeyAuthIT.partnerCall(port, json, secret, path, Map.of(), PartnerApiKeyAuthIT.freshIp());
    }

    /** Every unit via paging at {@code size}. */
    List<JsonNode> all(String secret, int size) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode body = get(secret, UNITS + "?page=" + page + "&size=" + size).body();
            body.get("content").forEach(out::add);
            if (page + 1 >= body.get("totalPages").asInt()) {
                return out;
            }
        }
    }

    static String sha(String s) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void countMatchesGrants() {
        Property p1 = property(orgA, "Tower");
        Property p2 = property(orgA, "Villa");
        Property p3 = property(orgA, "Ungranted");
        units(orgA, p1, 3);
        units(orgA, p2, 2);
        units(orgA, p3, 4);
        User promoter = promoter(orgA, false, List.of(), List.of(p1.getId(), p2.getId()));

        PartnerApiKeyAuthIT.PartnerResp res = get(keyFor(promoter), COUNT);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body().get("properties").asInt()).isEqualTo(2);
        assertThat(res.body().get("units").asLong()).isEqualTo(5);
        assertThat(res.text("fingerprint")).startsWith("sha256:");
        assertThat(res.text("generatedAt")).isNotBlank();
    }

    @Test
    void pagesInStableOrder() {
        Property p1 = property(orgA, "Tower");
        Property p2 = property(orgA, "Villa");
        units(orgA, p1, 3);
        units(orgA, p2, 2);
        User promoter = promoter(orgA, false, List.of(), List.of(p1.getId(), p2.getId()));
        String key = keyFor(promoter);

        JsonNode first = get(key, UNITS + "?page=0&size=2").body();
        assertThat(first.get("totalElements").asLong()).isEqualTo(5);
        assertThat(first.get("totalPages").asInt()).isEqualTo(3);
        assertThat(first.get("size").asInt()).isEqualTo(2);
        assertThat(first.get("page").asInt()).isZero();

        List<JsonNode> paged = all(key, 2);
        List<JsonNode> whole = all(key, 100);
        assertThat(paged).hasSize(5).isEqualTo(whole);
        // order: propertyName, unitNumber, unitId
        List<String> names = whole.stream().map(n -> n.get("propertyName").asText()).toList();
        assertThat(names).isSorted();
        assertThat(get(key, UNITS + "?page=3&size=2").body().get("content")).isEmpty();
    }

    /**
     * Final review M1: a malformed query parameter is the global handler's 400, not a
     * partner error code. The developer guide documents exactly this shape.
     */
    @Test
    void malformedQueryParameterIsDocumented400() {
        User promoter = promoter(orgA, false, List.of(), List.of());
        String key = keyFor(promoter);
        for (String[] c : new String[][]{{"propertyId=abc", "propertyId"}, {"page=x", "page"}, {"size=big", "size"}}) {
            PartnerApiKeyAuthIT.PartnerResp res = get(key, UNITS + "?" + c[0]);
            assertThat(res.status()).as(c[0]).isEqualTo(400);
            assertThat(res.body().get("error").asBoolean()).as(c[0]).isTrue();
            assertThat(res.body().get("status").asInt()).as(c[0]).isEqualTo(400);
            assertThat(res.text("message")).as(c[0]).isEqualTo("Invalid value for parameter '" + c[1] + "'");
        }
    }

    @Test
    void sizeIsClampedTo500() {
        Property p = property(orgA, "Big");
        for (int i = 0; i < 3; i++) {
            unit(orgA, p, "B-" + i);
        }
        User promoter = promoter(orgA, false, List.of(), List.of(p.getId()));
        String key = keyFor(promoter);
        assertThat(get(key, UNITS + "?size=100000").body().get("size").asInt()).isEqualTo(500);
        assertThat(get(key, UNITS + "?size=0").body().get("size").asInt()).isEqualTo(100);
        assertThat(get(key, UNITS + "?size=-5").body().get("size").asInt()).isEqualTo(100);
        assertThat(get(key, UNITS).body().get("size").asInt()).isEqualTo(100);
        PartnerApiKeyAuthIT.PartnerResp neg = get(key, UNITS + "?page=-3");
        assertThat(neg.body().get("page").asInt()).isZero();
        assertThat(neg.body().get("content")).hasSize(3);
    }

    @Test
    void otherOrgsUnitsNeverAppear() {
        UUID orgB = org();
        Property pa = property(orgA, "A-Tower");
        Property pb = property(orgB, "B-Tower");
        units(orgA, pa, 2);
        units(orgB, pb, 3);
        User promA = promoter(orgA, false, List.of(), List.of(pa.getId()));
        User promB = promoter(orgB, false, List.of(), List.of(pb.getId()));

        assertThat(all(keyFor(promA), 100)).extracting(n -> n.get("propertyId").asText())
                .containsOnly(pa.getId().toString()).hasSize(2);
        assertThat(all(keyFor(promB), 100)).extracting(n -> n.get("propertyId").asText())
                .containsOnly(pb.getId().toString()).hasSize(3);
    }

    /** A grant row that (corruptly) points at another org's property must still leak nothing. */
    @Test
    void grantToForeignOrgPropertyLeaksNothing() {
        UUID orgB = org();
        Property pb = property(orgB, "B-Tower");
        units(orgB, pb, 2);
        User promA = promoter(orgA, false, List.of(), List.of());
        jdbc.update("INSERT INTO promoter_property(user_id, property_id, tenant_id) VALUES (?, ?, ?)",
                promA.getId(), pb.getId(), orgA);
        String key = keyFor(promA);
        assertThat(get(key, UNITS).body().get("totalElements").asLong()).isZero();
        assertThat(get(key, COUNT).body().get("units").asLong()).isZero();
    }

    @Test
    void otherPromoterInSameOrgSeesOnlyOwnGrants() {
        Property p1 = property(orgA, "One");
        Property p2 = property(orgA, "Two");
        units(orgA, p1, 2);
        units(orgA, p2, 3);
        User x = promoter(orgA, false, List.of(), List.of(p1.getId()));
        User y = promoter(orgA, false, List.of(), List.of(p2.getId()));
        assertThat(all(keyFor(x), 100)).extracting(n -> n.get("propertyId").asText())
                .containsOnly(p1.getId().toString()).hasSize(2);
        assertThat(all(keyFor(y), 100)).extracting(n -> n.get("propertyId").asText())
                .containsOnly(p2.getId().toString()).hasSize(3);
    }

    @Test
    void grantRemovalShrinksCountAndChangesFingerprint() {
        Property p1 = property(orgA, "One");
        Property p2 = property(orgA, "Two");
        units(orgA, p1, 2);
        units(orgA, p2, 3);
        User promoter = promoter(orgA, false, List.of(), List.of(p1.getId(), p2.getId()));
        String key = keyFor(promoter);
        JsonNode before = get(key, COUNT).body();
        assertThat(before.get("units").asLong()).isEqualTo(5);

        jdbc.update("DELETE FROM promoter_property WHERE user_id = ? AND property_id = ?",
                promoter.getId(), p2.getId());

        JsonNode after = get(key, COUNT).body();
        assertThat(after.get("units").asLong()).isEqualTo(2);
        assertThat(after.get("properties").asInt()).isEqualTo(1);
        assertThat(after.get("fingerprint").asText()).isNotEqualTo(before.get("fingerprint").asText());
        assertThat(get(key, UNITS).body().get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void renamingPropertyChangesFingerprint() {
        Property p = property(orgA, "Old");
        units(orgA, p, 1);
        User promoter = promoter(orgA, false, List.of(), List.of(p.getId()));
        String key = keyFor(promoter);
        String before = get(key, COUNT).text("fingerprint");
        jdbc.update("UPDATE properties SET name_en = ? WHERE id = ?", "Renamed " + UUID.randomUUID(), p.getId());
        assertThat(get(key, COUNT).text("fingerprint")).isNotEqualTo(before);
    }

    @Test
    void duplicateUnitNumbersAcrossPropertiesAreDistinctRows() {
        Property p1 = property(orgA, "One");
        Property p2 = property(orgA, "Two");
        Unit u1 = unit(orgA, p1, "101");
        Unit u2 = unit(orgA, p2, "101");
        User promoter = promoter(orgA, false, List.of(), List.of(p1.getId(), p2.getId()));
        String key = keyFor(promoter);

        List<JsonNode> rows = all(key, 100);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(n -> n.get("unitNumber").asText()).containsExactly("101", "101");
        assertThat(rows).extracting(n -> n.get("unitId").asText())
                .containsExactlyInAnyOrder(u1.getId().toString(), u2.getId().toString());
        assertThat(rows.get(0).get("propertyId")).isNotEqualTo(rows.get(1).get("propertyId"));

        String fp = get(key, COUNT).text("fingerprint");
        assertThat(get(key, COUNT).text("fingerprint")).isEqualTo(fp);
        assertThat(get(key, UNITS).text("fingerprint")).isEqualTo(fp);
    }

    @Test
    void noGrantsIsAnEmptyFeed() {
        User promoter = promoter(orgA, false, List.of(), List.of());
        String key = keyFor(promoter);
        PartnerApiKeyAuthIT.PartnerResp count = get(key, COUNT);
        assertThat(count.status()).isEqualTo(200);
        assertThat(count.body().get("properties").asInt()).isZero();
        assertThat(count.body().get("units").asLong()).isZero();
        assertThat(count.text("fingerprint")).isEqualTo(EMPTY_FP);

        PartnerApiKeyAuthIT.PartnerResp page = get(key, UNITS);
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body().get("content")).isEmpty();
        assertThat(page.body().get("totalElements").asLong()).isZero();
        assertThat(page.body().get("totalPages").asInt()).isZero();
        assertThat(page.text("fingerprint")).isEqualTo(EMPTY_FP);
    }

    @Test
    void grantedPropertyWithNoUnitsCountsAsProperty() {
        Property p = property(orgA, "Empty");
        User promoter = promoter(orgA, false, List.of(), List.of(p.getId()));
        JsonNode c = get(keyFor(promoter), COUNT).body();
        assertThat(c.get("properties").asInt()).isEqualTo(1);
        assertThat(c.get("units").asLong()).isZero();
    }

    @Test
    void foreignPropertyIdFilterIsEmpty() {
        Property mine = property(orgA, "Mine");
        units(orgA, mine, 2);
        UUID orgB = org();
        Property theirs = property(orgB, "Theirs");
        units(orgB, theirs, 2);
        User promoter = promoter(orgA, false, List.of(), List.of(mine.getId()));
        String key = keyFor(promoter);

        PartnerApiKeyAuthIT.PartnerResp foreign = get(key, UNITS + "?propertyId=" + theirs.getId());
        assertThat(foreign.status()).isEqualTo(200);
        assertThat(foreign.body().get("content")).isEmpty();
        assertThat(foreign.body().get("totalElements").asLong()).isZero();
        // fingerprint ignores the filter: still the full grant set
        assertThat(foreign.text("fingerprint")).isEqualTo(get(key, COUNT).text("fingerprint"));

        PartnerApiKeyAuthIT.PartnerResp own = get(key, UNITS + "?propertyId=" + mine.getId());
        assertThat(own.body().get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void fingerprintSameOnCountAndUnitsAndMatchesAlgorithm() {
        Property p1 = property(orgA, "Alpha");
        Property p2 = property(orgA, "Beta");
        units(orgA, p1, 2);
        units(orgA, p2, 2);
        User promoter = promoter(orgA, false, List.of(), List.of(p1.getId(), p2.getId()));
        String key = keyFor(promoter);
        String fp = get(key, COUNT).text("fingerprint");
        assertThat(get(key, UNITS + "?size=1&page=2").text("fingerprint")).isEqualTo(fp);

        List<String> rows = all(key, 100).stream().map(n -> n.get("unitId").asText() + "|"
                + n.get("unitNumber").asText() + "|" + n.get("propertyId").asText() + "|"
                + n.get("propertyName").asText()).toList();
        assertThat(sha(String.join("\n", rows))).isEqualTo(fp);
    }

    @Test
    void responseHasNoTenantOrLeaseFields() {
        Property p = property(orgA, "Tower");
        units(orgA, p, 1);
        User promoter = promoter(orgA, false, List.of(), List.of(p.getId()));
        JsonNode unit = get(keyFor(promoter), UNITS).body().get("content").get(0);
        List<String> fields = new ArrayList<>();
        Iterator<String> it = unit.fieldNames();
        it.forEachRemaining(fields::add);
        assertThat(new TreeSet<>(fields)).containsExactly("propertyId", "propertyName", "unitId", "unitNumber");
    }

    /**
     * Outside a transaction the Hibernate tenant filter is off, so this is the query's
     * own predicates alone: a foreign-org unit/property reached through a (corrupt)
     * grant, or another promoter's grant, must still not come back.
     */
    @Test
    void queryScopesTenantAndUserWithoutTheHibernateFilter() {
        UUID orgB = org();
        Property pa = property(orgA, "A-Tower");
        Property pb = property(orgB, "B-Tower");
        unit(orgA, pa, "1");
        unit(orgB, pb, "1");
        User promA = promoter(orgA, false, List.of(), List.of(pa.getId()));
        User other = promoter(orgA, false, List.of(), List.of(pa.getId()));
        jdbc.update("INSERT INTO promoter_property(user_id, property_id, tenant_id) VALUES (?, ?, ?)",
                promA.getId(), pb.getId(), orgA);

        assertThat(unitRepo.findPartnerFeedRows(orgA, promA.getId()))
                .extracting(r -> r.getPropertyId()).containsExactly(pa.getId());
        assertThat(unitRepo.findPartnerFeedRows(orgB, promA.getId())).isEmpty();
        assertThat(unitRepo.findPartnerFeedRows(orgA, UUID.randomUUID())).isEmpty();
        assertThat(unitRepo.findPartnerFeedRows(orgA, other.getId()))
                .extracting(r -> r.getPropertyId()).containsExactly(pa.getId());
    }
}
