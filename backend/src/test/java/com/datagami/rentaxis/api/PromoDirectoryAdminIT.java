package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Business directory, task 2: the admin API for directory categories under
 * {@code /api/v1/promotions/directory-categories}.
 *
 * <p>Same configuration as {@code PromoterImageRulesIT} (a mocked blob store, no
 * token secret), so the two share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PromoDirectoryAdminIT extends AbstractPromoterIT {

    private static final String BASE = "/api/v1/promotions/directory-categories";

    @MockitoBean BlobStorageService blob;

    private UUID org;
    private User admin;
    private PromoBusiness wash;
    private PromoBusiness cafe;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        wash = business(org, "QuickWash");
        cafe = business(org, "Cafe");
        // This organisation's container holds every promotions file; any other holds none.
        when(blob.exists(eq(org), anyString())).thenReturn(true);
    }

    @Test
    void create_list_update_archive_restore() {
        Resp created = post(admin, BASE, body("nameEn", "  Food  ", "nameAr", "طعام", "sortOrder", 2,
                "businessIds", ids(cafe.getId(), cafe.getId())));
        assertThat(created.status()).isEqualTo(201);
        String id = created.text("id");
        assertThat(created.text("nameEn")).isEqualTo("Food");
        assertThat(created.text("nameAr")).isEqualTo("طعام");
        assertThat(created.body().get("sortOrder").asInt()).isEqualTo(2);
        assertThat(created.body().get("active").asBoolean()).isTrue();
        assertThat(created.texts("businessIds")).containsExactly(cafe.getId().toString());
        assertThat(created.body().get("businessCount").asInt()).isEqualTo(1);
        assertThat(created.text("createdAt")).isNotNull();

        // Sorted by sort_order, then created_at: "Cars" (0) before "Food" (2) before "Later" (2, newer).
        String cars = post(admin, BASE, body("nameEn", "Cars", "businessIds", ids(wash.getId()))).text("id");
        String later = post(admin, BASE, body("nameEn", "Later", "sortOrder", 2)).text("id");
        assertThat(listIds(BASE)).containsExactly(cars, id, later);
        Resp page = get(admin, BASE + "?page=0&size=2");
        assertThat(page.body().get("totalElements").asLong()).isEqualTo(3);
        assertThat(page.body().get("content").size()).isEqualTo(2);

        // Another org's categories never show.
        User otherAdmin = user(org(), UserRole.TENANT_ADMIN);
        assertThat(listIds(BASE, otherAdmin)).isEmpty();

        // PUT replaces the link set.
        Resp updated = put(admin, BASE + "/" + id, body("nameEn", "Food & Drink", "sortOrder", 5, "active", false,
                "businessIds", ids(wash.getId())));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.text("nameEn")).isEqualTo("Food & Drink");
        assertThat(updated.text("nameAr")).isNull();
        assertThat(updated.body().get("active").asBoolean()).isFalse();
        assertThat(updated.texts("businessIds")).containsExactly(wash.getId().toString());
        assertThat(linkCount(id)).isEqualTo(1);
        Resp both = put(admin, BASE + "/" + id, body("nameEn", "Food & Drink", "sortOrder", 5,
                "businessIds", ids(wash.getId(), cafe.getId())));
        assertThat(both.texts("businessIds")).containsExactlyInAnyOrder(wash.getId().toString(), cafe.getId().toString());
        assertThat(both.body().get("businessCount").asInt()).isEqualTo(2);
        assertThat(put(admin, BASE + "/" + id, body("nameEn", "Food & Drink", "businessIds", List.of()))
                .body().get("businessCount").asInt()).isZero();
        assertThat(linkCount(id)).isZero();

        // Validation.
        assertThat(post(admin, BASE, body("nameEn", " ")).status()).isEqualTo(400);
        assertThat(post(admin, BASE, body("nameEn", "x".repeat(61))).status()).isEqualTo(400);

        // DELETE archives.
        Resp archived = delete(admin, BASE + "/" + id);
        assertThat(archived.status()).isEqualTo(200);
        assertThat(archived.body().get("active").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM promo_directory_categories WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(listIds(BASE)).containsExactly(cars, later);
        assertThat(listIds(BASE + "?archived=true")).containsExactly(id);
        assertThat(put(admin, BASE + "/" + id, body("nameEn", "Edit")).status()).isEqualTo(400);

        // Restore: back in the list, still inactive; a second restore is refused.
        Resp restored = post(admin, BASE + "/" + id + "/restore", null);
        assertThat(restored.status()).isEqualTo(200);
        assertThat(restored.body().get("active").asBoolean()).isFalse();
        assertThat(listIds(BASE)).contains(id);
        assertThat(listIds(BASE + "?archived=true")).isEmpty();
        assertThat(post(admin, BASE + "/" + id + "/restore", null).status()).isEqualTo(400);
    }

    @Test
    void businessIdsFromAnotherTenant_rejected400() {
        UUID other = org();
        PromoBusiness foreign = business(other, "Foreign");

        Resp create = post(admin, BASE, body("nameEn", "Food", "businessIds", ids(cafe.getId(), foreign.getId())));
        assertThat(create.status()).isEqualTo(400);
        assertThat(create.text("code")).isEqualTo("UNKNOWN_BUSINESS");
        assertThat(post(admin, BASE, body("nameEn", "Food", "businessIds", ids(UUID.randomUUID())))
                .text("code")).isEqualTo("UNKNOWN_BUSINESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_directory_categories WHERE tenant_id = ?::uuid",
                Long.class, org.toString())).isZero();

        String id = post(admin, BASE, body("nameEn", "Food", "businessIds", ids(cafe.getId()))).text("id");
        Resp update = put(admin, BASE + "/" + id, body("nameEn", "Food", "businessIds", ids(foreign.getId())));
        assertThat(update.status()).isEqualTo(400);
        assertThat(update.text("code")).isEqualTo("UNKNOWN_BUSINESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_directory_category_businesses WHERE business_id = ?::uuid",
                Long.class, foreign.getId().toString())).isZero();
        assertThat(linkCount(id)).isEqualTo(1);
    }

    @Test
    void otherTenantsCategory_404onPutDeleteRestore() {
        String id = post(admin, BASE, body("nameEn", "Food", "businessIds", ids(cafe.getId()))).text("id");
        UUID other = org();
        User intruder = user(other, UserRole.TENANT_ADMIN);
        PromoBusiness theirs = business(other, "Theirs");

        assertThat(put(intruder, BASE + "/" + id, body("nameEn", "Hijack", "businessIds", ids(theirs.getId())))
                .status()).isEqualTo(404);
        assertThat(delete(intruder, BASE + "/" + id).status()).isEqualTo(404);
        delete(admin, BASE + "/" + id);
        assertThat(post(intruder, BASE + "/" + id + "/restore", null).status()).isEqualTo(404);
        assertThat(put(intruder, BASE + "/" + UUID.randomUUID(), body("nameEn", "x")).status()).isEqualTo(404);

        // Nothing changed.
        assertThat(jdbc.queryForObject("SELECT name_en FROM promo_directory_categories WHERE id = ?::uuid",
                String.class, id)).isEqualTo("Food");
        assertThat(jdbc.queryForObject("SELECT archived_at IS NOT NULL FROM promo_directory_categories WHERE id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(linkCount(id)).isEqualTo(1);
    }

    @Test
    void renterAndPropertyManager_forbidden403() {
        String id = post(admin, BASE, body("nameEn", "Food")).text("id");
        Property p1 = property(org, "Marina");
        for (User caller : List.of(renterAt(org, p1), user(org, UserRole.PROPERTY_MANAGER))) {
            assertThat(get(caller, BASE).status()).isEqualTo(403);
            assertThat(post(caller, BASE, body("nameEn", "Mine")).status()).isEqualTo(403);
            assertThat(put(caller, BASE + "/" + id, body("nameEn", "Mine")).status()).isEqualTo(403);
            assertThat(delete(caller, BASE + "/" + id).status()).isEqualTo(403);
            assertThat(post(caller, BASE + "/" + id + "/restore", null).status()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promo_directory_categories WHERE tenant_id = ?::uuid",
                Long.class, org.toString())).isEqualTo(1);
    }

    /**
     * Ruling R3: the icon gets exactly the business {@code logoUrl} rule — an upload
     * must be this organisation's own; a linked image must be https.
     */
    @Test
    void iconUrl_mustBeOwnUpload() {
        String file = UUID.randomUUID() + ".png";
        String foreignRoute = "/api/v1/public/promo-images/" + file;
        when(blob.exists(org, "promotions/" + file)).thenReturn(false);

        Resp foreign = post(admin, BASE, body("nameEn", "Food", "iconUrl", foreignRoute));
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.text("message")).contains("iconUrl");
        assertThat(post(admin, BASE, body("nameEn", "Food", "iconUrl", "/api/v1/public/promo-images/not-a-file"))
                .status()).isEqualTo(400);
        assertThat(post(admin, BASE, body("nameEn", "Food", "iconUrl", "http://cdn.example/a.png")).status())
                .isEqualTo(400);
        assertThat(post(admin, BASE, body("nameEn", "Food", "iconUrl", "javascript:alert(1)")).status())
                .isEqualTo(400);

        String own = "/api/v1/public/promo-images/" + UUID.randomUUID() + ".png";
        Resp ok = post(admin, BASE, body("nameEn", "Food", "iconUrl", own));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.text("iconUrl")).endsWith(own);
        assertThat(put(admin, BASE + "/" + ok.text("id"), body("nameEn", "Food", "iconUrl", foreignRoute)).status())
                .isEqualTo(400);
        // An admin keeps https links, as for a business logo.
        assertThat(post(admin, BASE, body("nameEn", "Cars", "iconUrl", "https://cdn.example/a.png")).status())
                .isEqualTo(201);
    }

    // ----------------------------------------------------------------- helpers

    private List<String> listIds(String path) {
        return listIds(path, admin);
    }

    private List<String> listIds(String path, User caller) {
        Resp res = get(caller, path);
        assertThat(res.status()).isEqualTo(200);
        return StreamSupport.stream(res.body().get("content").spliterator(), false)
                .map(n -> n.get("id").asText()).toList();
    }

    private long linkCount(String categoryId) {
        return jdbc.queryForObject("SELECT count(*) FROM promo_directory_category_businesses WHERE category_id = ?::uuid",
                Long.class, categoryId);
    }
}
