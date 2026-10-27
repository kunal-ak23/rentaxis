package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter plan addendum A: a COUPON ad may open a link after "Copy code", with
 * query parameters whose names the admin chooses and whose values the renter app's
 * data fills — validated on write, resolved per renter in the feed.
 */
class PromotionCouponRedirectIT extends AbstractPromoterIT {

    private static final String LINK = "https://app.quickwash.example/redeem?src=miftah";

    private UUID org;
    private User admin;
    private String businessId;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        Resp biz = post(admin, "/api/v1/promotions/businesses", body(
                "nameEn", "QuickWash " + UUID.randomUUID().toString().substring(0, 6),
                "allowedDomains", List.of("quickwash.example")));
        assertThat(biz.status()).isEqualTo(201);
        businessId = biz.text("id");
    }

    // ------------------------------------------------------------ validation

    @Test
    void aValidRedirectIsStoredAndReturned() {
        Resp res = post(admin, "/api/v1/promotions/ads", coupon(LINK, List.of(
                param("code", "COUPON_CODE", null),
                param("ch", "STATIC", "  miftah app "),
                param("n", "RENTER_NAME", "ignored for a non-static source"))));

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.text("redirectUrl")).isEqualTo(LINK);
        JsonNode params = res.body().get("redirectParams");
        assertThat(params).hasSize(3);
        assertThat(params.get(0).get("name").asText()).isEqualTo("code");
        assertThat(params.get(0).get("source").asText()).isEqualTo("COUPON_CODE");
        assertThat(params.get(0).get("value").isNull()).isTrue();
        assertThat(params.get(1).get("value").asText()).isEqualTo("miftah app");
        assertThat(params.get(2).get("value").isNull()).isTrue();

        // Read back from the database through the list.
        JsonNode listed = adById(res.text("id"));
        assertThat(listed.get("redirectUrl").asText()).isEqualTo(LINK);
        assertThat(listed.get("redirectParams")).hasSize(3);
    }

    @Test
    void anyOtherCtaTypeClearsTheRedirect() {
        Map<String, Object> req = coupon(LINK, List.of(param("code", "COUPON_CODE", null)));
        req.put("ctaType", "WEBSITE");
        req.put("ctaUrl", "https://quickwash.example/offer");

        Resp res = post(admin, "/api/v1/promotions/ads", req);

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("redirectUrl").isNull()).isTrue();
        assertThat(res.body().get("redirectParams")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT redirect_params IS NULL AND redirect_url IS NULL FROM promo_ads WHERE id = ?::uuid",
                Boolean.class, res.text("id"))).isTrue();
    }

    @Test
    void badRedirectsAreRefused() {
        assertRefused(coupon(LINK, List.of(param("bad name!", "COUPON_CODE", null))), "not valid");
        assertRefused(coupon(LINK, List.of(param("x".repeat(41), "COUPON_CODE", null))), "not valid");
        assertRefused(coupon(LINK, List.of(param("code", "COUPON_CODE", null), param("code", "RENTER_NAME", null))),
                "twice");
        List<Map<String, Object>> eleven = new ArrayList<>();
        IntStream.range(0, 11).forEach(i -> eleven.add(param("p" + i, "COUPON_CODE", null)));
        assertRefused(coupon(LINK, eleven), "at most 10");
        assertRefused(coupon(LINK, List.of(param("ch", "STATIC", "  "))), "fixed value");
        assertRefused(coupon(LINK, List.of(param("ch", "STATIC", "v".repeat(201)))), "at most 200");
        assertRefused(coupon(LINK, List.of(param("ch", null, null))), "needs a source");
        assertRefused(coupon(null, List.of(param("code", "COUPON_CODE", null))), "need a redirectUrl");
        assertRefused(coupon("https://elsewhere.example/redeem", List.of()), "allowed domains");
        assertRefused(coupon("http://app.quickwash.example/redeem", List.of()), "allowed domains");
        assertRefused(coupon("https://user@app.quickwash.example/redeem", List.of()), "allowed domains");
    }

    // ------------------------------------------------------------ resolution

    @Test
    void theFeedResolvesTheLinkForTheCallingRenter() {
        Property marina = property(org, "Marina");
        Property creek = property(org, "Creek");
        User renter = user(org, UserRole.RENTER);
        Renter record = renterRecord(renter, "Aisha Khan", "+971 50 123 4567", null);
        lease(record, marina, "101", LocalDate.now().minusMonths(6));  // earliest
        lease(record, creek, "B-7", LocalDate.now().minusMonths(1));

        List<Map<String, Object>> params = List.of(
                param("code", "COUPON_CODE", null),
                param("n", "RENTER_NAME", null),
                param("p", "RENTER_PHONE", null),
                param("e", "RENTER_EMAIL", null),
                param("prop", "PROPERTY_NAME", null),
                param("unit", "UNIT_NUMBER", null),
                param("ch", "STATIC", "miftah app"));
        Map<String, Object> targeted = coupon(LINK, params);
        targeted.put("propertyIds", List.of(creek.getId().toString()));
        String targetedId = post(admin, "/api/v1/promotions/ads", targeted).text("id");
        String everywhereId = post(admin, "/api/v1/promotions/ads", coupon(LINK + "#top", params)).text("id");
        String plainCouponId = post(admin, "/api/v1/promotions/ads", coupon(null, List.of())).text("id");
        String onlyOursId = post(admin, "/api/v1/promotions/ads", coupon("https://app.quickwash.example/r",
                List.of(param("code", "COUPON_CODE", null), param("ch", "STATIC", "x")))).text("id");

        Map<String, JsonNode> cards = offers(renter);

        String email = enc(renter.getEmail());
        // Targets Creek: property and unit from the Creek lease; the link's own query kept.
        assertThat(cards.get(targetedId).get("redirectUrl").asText()).isEqualTo(LINK
                + "&code=SAVE10&n=Aisha%20Khan&p=%2B971%2050%20123%204567&e=" + email
                + "&prop=" + enc(creek.getNameEn()) + "&unit=B-7&ch=miftah%20app");
        assertThat(texts(cards.get(targetedId).get("sharedFields")))
                .containsExactly("RENTER_NAME", "RENTER_PHONE", "RENTER_EMAIL", "PROPERTY_NAME", "UNIT_NUMBER");
        // Targets every property: the earliest-starting active lease (Marina); the fragment stays last.
        assertThat(cards.get(everywhereId).get("redirectUrl").asText()).isEqualTo(LINK
                + "&code=SAVE10&n=Aisha%20Khan&p=%2B971%2050%20123%204567&e=" + email
                + "&prop=" + enc(marina.getNameEn()) + "&unit=101&ch=miftah%20app#top");
        // A coupon with no redirect: nothing to open, nothing shared.
        assertThat(cards.get(plainCouponId).get("redirectUrl").isNull()).isTrue();
        assertThat(cards.get(plainCouponId).get("sharedFields")).isEmpty();
        // Only the code and a fixed value: a link, but no renter data in it.
        assertThat(cards.get(onlyOursId).get("redirectUrl").asText())
                .isEqualTo("https://app.quickwash.example/r?code=SAVE10&ch=x");
        assertThat(cards.get(onlyOursId).get("sharedFields")).isEmpty();
    }

    @Test
    void valuesTheRenterDoesNotHaveAreLeftOut() {
        // No lease (an untargeted ad still reaches them) and no phone anywhere.
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        String adId = post(admin, "/api/v1/promotions/ads", coupon("https://app.quickwash.example/r", List.of(
                param("n", "RENTER_NAME", null),
                param("p", "RENTER_PHONE", null),
                param("prop", "PROPERTY_NAME", null),
                param("unit", "UNIT_NUMBER", null)))).text("id");

        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").asText()).isEqualTo("https://app.quickwash.example/r?n=Omar");
        assertThat(texts(card.get("sharedFields"))).containsExactly("RENTER_NAME");
    }

    @Test
    void aWebsiteAdCarriesNoRedirect() {
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        Map<String, Object> req = coupon(null, List.of());
        req.put("ctaType", "WEBSITE");
        req.put("ctaUrl", "https://quickwash.example/offer");
        String adId = post(admin, "/api/v1/promotions/ads", req).text("id");

        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").isNull()).isTrue();
        assertThat(card.get("sharedFields")).isEmpty();
    }

    @Test
    void aDomainDroppedFromTheAllowlistStopsTheRedirect() {
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        String adId = post(admin, "/api/v1/promotions/ads", coupon(LINK, List.of(param("n", "RENTER_NAME", null))))
                .text("id");
        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'other.example' WHERE id = ?::uuid", businessId);

        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").isNull()).isTrue();
        assertThat(card.get("sharedFields")).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> coupon(String redirectUrl, List<Map<String, Object>> params) {
        return body("businessId", businessId, "titleEn", "Wash day", "ctaType", "COUPON",
                "couponCode", "SAVE10", "redirectUrl", redirectUrl, "redirectParams", params);
    }

    private static Map<String, Object> param(String name, String source, String value) {
        return body("name", name, "source", source, "value", value);
    }

    private void assertRefused(Map<String, Object> req, String messagePart) {
        Resp res = post(admin, "/api/v1/promotions/ads", req);
        assertThat(res.status()).as(String.valueOf(req)).isEqualTo(400);
        assertThat(res.text("message")).as(String.valueOf(req)).containsIgnoringCase(messagePart);
    }

    private JsonNode adById(String id) {
        JsonNode page = get(admin, "/api/v1/promotions/ads?size=100").body().get("content");
        return StreamSupport.stream(page.spliterator(), false)
                .filter(a -> a.get("id").asText().equals(id)).findFirst().orElseThrow();
    }

    private Map<String, JsonNode> offers(User renter) {
        Resp res = get(renter, "/api/v1/promotions/offers");
        assertThat(res.status()).isEqualTo(200);
        Map<String, JsonNode> byId = new java.util.HashMap<>();
        res.body().forEach(card -> byId.put(card.get("id").asText(), card));
        return byId;
    }

    private Renter renterRecord(User user, String name, String phone, String email) {
        TenantContextHolder.setTenantId(org);
        try {
            Renter r = new Renter();
            r.setNameEn(name);
            r.setPhone(phone);
            r.setEmail(email);
            r.setUserId(user.getId());
            return renterRepo.save(r);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private void lease(Renter renter, Property property, String unitNumber, LocalDate start) {
        TenantContextHolder.setTenantId(org);
        try {
            Unit unit = new Unit();
            unit.setProperty(property);
            unit.setUnitNumber(unitNumber);
            unit.setTenantId(org);
            unit = unitRepo.save(unit);
            Lease lease = new Lease();
            lease.setUnit(unit);
            lease.setRenter(renter);
            lease.setTenantId(org);
            lease.setStartDate(start);
            lease.setEndDate(start.plusYears(1));
            lease.setRentAmount(BigDecimal.valueOf(60000));
            lease.setStatus(LeaseStatus.ACTIVE);
            leaseRepo.save(lease);
        } finally {
            TenantContextHolder.clear();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> texts(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).toList();
    }
}
