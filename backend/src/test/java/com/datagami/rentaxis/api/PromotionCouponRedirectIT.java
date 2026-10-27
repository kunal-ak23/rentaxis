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
 *
 * <p>Addendum C: optional Android and iPhone links beside it, validated and resolved
 * exactly like it, with the same parameters.
 */
class PromotionCouponRedirectIT extends AbstractPromoterIT {

    private static final String LINK = "https://app.quickwash.example/redeem?src=miftah";
    private static final String ANDROID = "https://android.quickwash.example/open?src=miftah";
    private static final String IOS = "https://ios.quickwash.example/open";

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
        Map<String, Object> req = coupon(LINK, ANDROID, IOS, List.of(param("code", "COUPON_CODE", null)));
        req.put("ctaType", "WEBSITE");
        req.put("ctaUrl", "https://quickwash.example/offer");

        Resp res = post(admin, "/api/v1/promotions/ads", req);

        assertThat(res.status()).isEqualTo(201);
        assertThat(res.body().get("redirectUrl").isNull()).isTrue();
        assertThat(res.body().get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(res.body().get("redirectUrlIos").isNull()).isTrue();
        assertThat(res.body().get("redirectParams")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT redirect_params IS NULL AND redirect_url IS NULL"
                        + " AND redirect_url_android IS NULL AND redirect_url_ios IS NULL FROM promo_ads WHERE id = ?::uuid",
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
        assertRefused(coupon(null, List.of(param("code", "COUPON_CODE", null))), "need a link");
        assertRefused(coupon("https://elsewhere.example/redeem", List.of()), "allowed domains");
        assertRefused(coupon("http://app.quickwash.example/redeem", List.of()), "allowed domains");
        assertRefused(coupon("https://user@app.quickwash.example/redeem", List.of()), "allowed domains");
    }

    @Test
    void platformLinksAreStoredAndReturned() {
        List<Map<String, Object>> params = List.of(param("code", "COUPON_CODE", null));

        // Parameters with only a platform link (no main link) are accepted.
        Resp android = post(admin, "/api/v1/promotions/ads", coupon(null, "  " + ANDROID + " ", null, params));
        Resp ios = post(admin, "/api/v1/promotions/ads", coupon(null, null, IOS, params));
        Resp all = post(admin, "/api/v1/promotions/ads", coupon(LINK, ANDROID, IOS, params));

        assertThat(android.status()).isEqualTo(201);
        assertThat(android.body().get("redirectUrl").isNull()).isTrue();
        assertThat(android.text("redirectUrlAndroid")).isEqualTo(ANDROID);
        assertThat(android.body().get("redirectUrlIos").isNull()).isTrue();
        assertThat(android.body().get("redirectParams")).hasSize(1);
        assertThat(ios.status()).isEqualTo(201);
        assertThat(ios.body().get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(ios.text("redirectUrlIos")).isEqualTo(IOS);
        assertThat(ios.body().get("redirectParams")).hasSize(1);
        assertThat(all.status()).isEqualTo(201);

        // Read back from the database through the list, and from the columns.
        JsonNode listed = adById(all.text("id"));
        assertThat(listed.get("redirectUrl").asText()).isEqualTo(LINK);
        assertThat(listed.get("redirectUrlAndroid").asText()).isEqualTo(ANDROID);
        assertThat(listed.get("redirectUrlIos").asText()).isEqualTo(IOS);
        assertThat(jdbc.queryForObject("SELECT redirect_url_android || ' ' || redirect_url_ios FROM promo_ads"
                + " WHERE id = ?::uuid", String.class, all.text("id"))).isEqualTo(ANDROID + " " + IOS);
        assertThat(adById(android.text("id")).get("redirectUrlAndroid").asText()).isEqualTo(ANDROID);
        assertThat(adById(ios.text("id")).get("redirectUrlIos").asText()).isEqualTo(IOS);
    }

    @Test
    void badPlatformLinksAreRefusedLikeTheMainLink() {
        String longPath = "https://app.quickwash.example/" + "a".repeat(2049 - "https://app.quickwash.example/".length());
        assertThat(longPath).hasSize(2049);
        for (String field : List.of("redirectUrlAndroid", "redirectUrlIos")) {
            assertRefused(platform(field, "https://elsewhere.example/open"), field + " must be https");
            // A store page needs its domain on the allowlist like any other host.
            assertRefused(platform(field, "https://play.google.com/store/apps/details?id=com.quickwash"),
                    field + " must be https");
            assertRefused(platform(field, "https://apps.apple.com/app/id123"), field + " must be https");
            assertRefused(platform(field, "http://app.quickwash.example/open"), field + " must be https");
            assertRefused(platform(field, "https://user@app.quickwash.example/open"), field + " must be https");
            assertRefused(platform(field, "app.quickwash.example/open"), field + " must be https");
            assertRefused(platform(field, longPath), field);
        }
        // No link of any kind: the parameters have nowhere to go.
        assertRefused(coupon(null, null, null, List.of(param("code", "COUPON_CODE", null))), "need a link");
        assertRefused(coupon(" ", "", "  ", List.of(param("code", "COUPON_CODE", null))), "need a link");
    }

    // ------------------------------------------------------------ resolution

    @Test
    void theFeedResolvesEachPlatformLinkLikeTheMainOne() {
        Property marina = property(org, "Marina");
        User renter = user(org, UserRole.RENTER);
        Renter record = renterRecord(renter, "Aisha Khan", "+971 50 123 4567", null);
        lease(record, marina, "101", LocalDate.now().minusMonths(6));
        List<Map<String, Object>> params = List.of(
                param("code", "COUPON_CODE", null),
                param("n", "RENTER_NAME", null),
                param("p", "RENTER_PHONE", null),
                param("prop", "PROPERTY_NAME", null),
                param("ch", "STATIC", "miftah app"));
        String allId = post(admin, "/api/v1/promotions/ads", coupon(LINK, ANDROID, IOS + "#top", params)).text("id");
        String mainOnlyId = post(admin, "/api/v1/promotions/ads", coupon(LINK, null, null, params)).text("id");
        String plainCouponId = post(admin, "/api/v1/promotions/ads", coupon(null, List.of())).text("id");

        Map<String, JsonNode> cards = offers(renter);

        String query = "code=SAVE10&n=Aisha%20Khan&p=%2B971%2050%20123%204567&prop=" + enc(marina.getNameEn())
                + "&ch=miftah%20app";
        JsonNode all = cards.get(allId);
        assertThat(all.get("redirectUrl").asText()).isEqualTo(LINK + "&" + query);
        // The Android link's own query kept; the iPhone link gains one, its fragment last.
        assertThat(all.get("redirectUrlAndroid").asText()).isEqualTo(ANDROID + "&" + query);
        assertThat(all.get("redirectUrlIos").asText()).isEqualTo(IOS + "?" + query + "#top");
        assertThat(texts(all.get("sharedFields")))
                .containsExactly("RENTER_NAME", "RENTER_PHONE", "PROPERTY_NAME");
        // No platform links set: null, and the main link as before.
        JsonNode mainOnly = cards.get(mainOnlyId);
        assertThat(mainOnly.get("redirectUrl").asText()).isEqualTo(LINK + "&" + query);
        assertThat(mainOnly.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(mainOnly.get("redirectUrlIos").isNull()).isTrue();
        JsonNode plain = cards.get(plainCouponId);
        assertThat(plain.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(plain.get("redirectUrlIos").isNull()).isTrue();
    }

    @Test
    void aPlatformLinkAloneCarriesTheSharedFields() {
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        List<Map<String, Object>> params = List.of(param("n", "RENTER_NAME", null), param("p", "RENTER_PHONE", null),
                param("e", "RENTER_EMAIL", null));
        String androidId = post(admin, "/api/v1/promotions/ads", coupon(null, ANDROID, null, params)).text("id");
        String iosId = post(admin, "/api/v1/promotions/ads", coupon(null, null, IOS, params)).text("id");

        Map<String, JsonNode> cards = offers(renter);

        // No phone anywhere: left out, as for the main link.
        String query = "n=Omar&e=" + enc(renter.getEmail());
        JsonNode android = cards.get(androidId);
        assertThat(android.get("redirectUrl").isNull()).isTrue();
        assertThat(android.get("redirectUrlAndroid").asText()).isEqualTo(ANDROID + "&" + query);
        assertThat(android.get("redirectUrlIos").isNull()).isTrue();
        assertThat(texts(android.get("sharedFields"))).containsExactly("RENTER_NAME", "RENTER_EMAIL");
        JsonNode ios = cards.get(iosId);
        assertThat(ios.get("redirectUrl").isNull()).isTrue();
        assertThat(ios.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(ios.get("redirectUrlIos").asText()).isEqualTo(IOS + "?" + query);
        assertThat(texts(ios.get("sharedFields"))).containsExactly("RENTER_NAME", "RENTER_EMAIL");
    }

    @Test
    void aStorePageDroppedFromTheAllowlistStopsOnlyThatLink() {
        String play = "https://play.google.com/store/apps/details?id=com.quickwash";
        String appStore = "https://apps.apple.com/app/id123";
        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'quickwash.example,play.google.com,apps.apple.com'"
                + " WHERE id = ?::uuid", businessId);
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        String adId = post(admin, "/api/v1/promotions/ads",
                coupon(LINK, play, appStore, List.of(param("n", "RENTER_NAME", null)))).text("id");
        JsonNode before = offers(renter).get(adId);
        assertThat(before.get("redirectUrlAndroid").asText()).isEqualTo(play + "&n=Omar");
        assertThat(before.get("redirectUrlIos").asText()).isEqualTo(appStore + "?n=Omar");

        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'quickwash.example,apps.apple.com'"
                + " WHERE id = ?::uuid", businessId);
        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").asText()).isEqualTo(LINK + "&n=Omar");
        assertThat(card.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(card.get("redirectUrlIos").asText()).isEqualTo(appStore + "?n=Omar");
        assertThat(texts(card.get("sharedFields"))).containsExactly("RENTER_NAME");
    }

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
        Map<String, Object> req = coupon(null, ANDROID, IOS, List.of());
        req.put("ctaType", "WEBSITE");
        req.put("ctaUrl", "https://quickwash.example/offer");
        String adId = post(admin, "/api/v1/promotions/ads", req).text("id");

        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").isNull()).isTrue();
        assertThat(card.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(card.get("redirectUrlIos").isNull()).isTrue();
        assertThat(card.get("sharedFields")).isEmpty();
    }

    @Test
    void aDomainDroppedFromTheAllowlistStopsTheRedirect() {
        User renter = user(org, UserRole.RENTER);
        renterRecord(renter, "Omar", null, null);
        String adId = post(admin, "/api/v1/promotions/ads",
                coupon(LINK, ANDROID, IOS, List.of(param("n", "RENTER_NAME", null)))).text("id");
        jdbc.update("UPDATE promo_businesses SET allowed_domains = 'other.example' WHERE id = ?::uuid", businessId);

        JsonNode card = offers(renter).get(adId);

        assertThat(card.get("redirectUrl").isNull()).isTrue();
        assertThat(card.get("redirectUrlAndroid").isNull()).isTrue();
        assertThat(card.get("redirectUrlIos").isNull()).isTrue();
        assertThat(card.get("sharedFields")).isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> coupon(String redirectUrl, List<Map<String, Object>> params) {
        return body("businessId", businessId, "titleEn", "Wash day", "ctaType", "COUPON",
                "couponCode", "SAVE10", "redirectUrl", redirectUrl, "redirectParams", params);
    }

    private Map<String, Object> coupon(String redirectUrl, String android, String ios,
                                       List<Map<String, Object>> params) {
        Map<String, Object> req = coupon(redirectUrl, params);
        req.put("redirectUrlAndroid", android);
        req.put("redirectUrlIos", ios);
        return req;
    }

    /** A coupon with only {@code field} (one of the platform links) set, and no parameters. */
    private Map<String, Object> platform(String field, String url) {
        Map<String, Object> req = coupon(null, List.of());
        req.put(field, url);
        return req;
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
