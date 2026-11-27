package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.PromoDirectoryCategory;
import com.datagami.rentaxis.domain.entity.PromoDirectoryLink;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.PromoDirectoryCategoryRepository;
import com.datagami.rentaxis.domain.repository.PromoDirectoryLinkRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business directory, task 3: the renter reads under {@code /api/v1/promotions/directory}
 * and {@code /api/v1/promotions/offers?businessId=}.
 *
 * <p>No extra configuration, so it shares {@code PromoterFeedIT}'s application context.
 */
class PromoDirectoryRenterIT extends AbstractPromoterIT {

    private static final String DIR = "/api/v1/promotions/directory";

    @Autowired PromoDirectoryCategoryRepository categoryRepo;
    @Autowired PromoDirectoryLinkRepository linkRepo;

    private UUID org;
    private Property marina;
    private User renter;

    @BeforeEach
    void setUp() {
        org = org();
        marina = property(org, "Marina");
        renter = renterAt(org, marina);
    }

    @Test
    void directory_hidesEmptyAndArchivedCategories() {
        PromoBusiness wash = business(org, "QuickWash");
        PromoBusiness cafe = business(org, "Cafe");
        PromoBusiness closed = business(org, "Closed");
        closed.setActive(false);
        businessRepo.save(closed);

        PromoDirectoryCategory food = category(org, "Food", 2, cafe, wash, closed);
        PromoDirectoryCategory cars = category(org, "Cars", 1, wash);
        category(org, "Empty", 0);
        category(org, "OnlyInactive", 0, closed);
        PromoDirectoryCategory archived = category(org, "Archived", 0, cafe);
        // Archived but still active: archivedAt alone must hide it.
        archived.setArchivedAt(Instant.now());
        categoryRepo.save(archived);
        PromoDirectoryCategory inactive = category(org, "Inactive", 0, cafe);
        inactive.setActive(false);
        categoryRepo.save(inactive);

        Resp res = get(renter, DIR);
        assertThat(res.status()).isEqualTo(200);
        // By sort order: Cars (1), then Food (2); the rest hidden.
        assertThat(ids(res.body())).containsExactly(cars.getId().toString(), food.getId().toString());
        JsonNode foodRow = res.body().get(1);
        assertThat(foodRow.get("nameEn").asText()).isEqualTo("Food");
        assertThat(foodRow.get("nameAr").asText()).isEqualTo("Food AR");
        assertThat(foodRow.get("iconUrl").asText()).isEqualTo("https://cdn.example/Food.png");
        // Only visible businesses count: the inactive one doesn't.
        assertThat(foodRow.get("businessCount").asInt()).isEqualTo(2);
        assertThat(res.body().get(0).get("businessCount").asInt()).isEqualTo(1);

        // Category detail lists its visible businesses, oldest first (wash was created before cafe).
        Resp detail = get(renter, DIR + "/" + food.getId());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("category").get("id").asText()).isEqualTo(food.getId().toString());
        assertThat(detail.body().get("category").get("businessCount").asInt()).isEqualTo(2);
        assertThat(ids(detail.body().get("businesses")))
                .containsExactly(wash.getId().toString(), cafe.getId().toString());

        // An archived or inactive category is a 404, as is an unknown id.
        assertThat(get(renter, DIR + "/" + archived.getId()).status()).isEqualTo(404);
        assertThat(get(renter, DIR + "/" + inactive.getId()).status()).isEqualTo(404);
        assertThat(get(renter, DIR + "/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void archivingOnlyBusiness_hidesCategory() {
        PromoBusiness wash = business(org, "QuickWash");
        PromoDirectoryCategory cars = category(org, "Cars", 0, wash);
        assertThat(ids(get(renter, DIR).body())).containsExactly(cars.getId().toString());

        wash.setArchivedAt(Instant.now());
        businessRepo.save(wash);

        assertThat(ids(get(renter, DIR).body())).isEmpty();
        Resp detail = get(renter, DIR + "/" + cars.getId());
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("businesses").size()).isZero();
        assertThat(detail.body().get("category").get("businessCount").asInt()).isZero();
        assertThat(get(renter, DIR + "/businesses/" + wash.getId()).status()).isEqualTo(404);
    }

    @Test
    void otherTenantCategoryAndBusiness_404() {
        UUID other = org();
        PromoBusiness theirs = business(other, "Theirs");
        PromoDirectoryCategory theirCategory = category(other, "Theirs", 0, theirs);
        // Sanity: the owner's renter sees it.
        User theirRenter = renterAt(other, property(other, "Creek"));
        assertThat(get(theirRenter, DIR + "/" + theirCategory.getId()).status()).isEqualTo(200);
        assertThat(get(theirRenter, DIR + "/businesses/" + theirs.getId()).status()).isEqualTo(200);

        assertThat(ids(get(renter, DIR).body())).isEmpty();
        Resp category = get(renter, DIR + "/" + theirCategory.getId());
        assertThat(category.status()).isEqualTo(404);
        assertThat(category.text("businesses")).isNull();
        Resp business = get(renter, DIR + "/businesses/" + theirs.getId());
        assertThat(business.status()).isEqualTo(404);
        assertThat(business.text("nameEn")).isNull();
        assertThat(offerIdsFor(renter, theirs.getId())).isEmpty();
    }

    @Test
    void businessInNoActiveCategory_404() {
        PromoBusiness loose = business(org, "Loose");
        assertThat(get(renter, DIR + "/businesses/" + loose.getId()).status()).isEqualTo(404);

        PromoDirectoryCategory archived = category(org, "Archived", 0, loose);
        archived.setArchivedAt(Instant.now());
        categoryRepo.save(archived);
        PromoDirectoryCategory inactive = category(org, "Inactive", 0, loose);
        inactive.setActive(false);
        categoryRepo.save(inactive);
        assertThat(get(renter, DIR + "/businesses/" + loose.getId()).status()).isEqualTo(404);

        category(org, "Live", 0, loose);
        assertThat(get(renter, DIR + "/businesses/" + loose.getId()).status()).isEqualTo(200);

        loose.setActive(false);
        businessRepo.save(loose);
        assertThat(get(renter, DIR + "/businesses/" + loose.getId()).status()).isEqualTo(404);
        assertThat(get(renter, DIR + "/businesses/" + UUID.randomUUID()).status()).isEqualTo(404);
    }

    @Test
    void businessListedRegardlessOfAds() {
        PromoBusiness b = business(org, "NoAds");
        b.setNameAr("بلا عروض");
        b.setLogoUrl("https://cdn.example/logo.png");
        b.setDescriptionEn("We wash cars.");
        b.setDescriptionAr("نغسل السيارات");
        b.setWebsiteUrl("https://quickwash.example");
        b.setWhatsappE164("+971509999999");
        businessRepo.save(b);
        PromoDirectoryCategory cars = category(org, "Cars", 0, b);

        assertThat(ids(get(renter, DIR).body())).containsExactly(cars.getId().toString());
        JsonNode row = get(renter, DIR + "/" + cars.getId()).body().get("businesses").get(0);
        assertBusiness(row, b);

        Resp detail = get(renter, DIR + "/businesses/" + b.getId());
        assertThat(detail.status()).isEqualTo(200);
        assertBusiness(detail.body(), b);
        assertThat(offerIdsFor(renter, b.getId())).isEmpty();
    }

    @Test
    void offersByBusiness_includesOffersOnlyPlacement_excludesOtherBusiness() {
        PromoBusiness wash = business(org, "QuickWash");
        PromoBusiness cafe = business(org, "Cafe");
        PromoAd home = ad(org, wash.getId(), APPROVED, null);
        PromoAd offersOnly = ad(org, wash.getId(), APPROVED, null);
        offersOnly.setPlacement(PromoPlacement.OFFERS_ONLY);
        adRepo.save(offersOnly);
        PromoAd cafeAd = ad(org, cafe.getId(), APPROVED, null);

        assertThat(offerIdsFor(renter, wash.getId()))
                .containsExactlyInAnyOrder(home.getId().toString(), offersOnly.getId().toString());
        assertThat(offerIdsFor(renter, cafe.getId())).containsExactly(cafeAd.getId().toString());
        // Unfiltered and category-filtered calls still work.
        assertThat(offerIds(renter)).contains(home.getId().toString(), offersOnly.getId().toString(),
                cafeAd.getId().toString());
        assertThat(get(renter, "/api/v1/promotions/offers?category=OTHER&businessId=" + wash.getId())
                .body().size()).isEqualTo(2);
        assertThat(get(renter, "/api/v1/promotions/offers?category=DINING&businessId=" + wash.getId())
                .body().size()).isZero();
    }

    @Test
    void managerRole_forbidden403() {
        PromoBusiness wash = business(org, "QuickWash");
        PromoDirectoryCategory cars = category(org, "Cars", 0, wash);
        for (User caller : List.of(user(org, UserRole.PROPERTY_MANAGER), user(org, UserRole.TENANT_ADMIN))) {
            assertThat(get(caller, DIR).status()).isEqualTo(403);
            assertThat(get(caller, DIR + "/" + cars.getId()).status()).isEqualTo(403);
            assertThat(get(caller, DIR + "/businesses/" + wash.getId()).status()).isEqualTo(403);
        }
    }

    // ----------------------------------------------------------------- helpers

    private PromoDirectoryCategory category(UUID tenantId, String name, int sortOrder, PromoBusiness... linked) {
        PromoDirectoryCategory c = new PromoDirectoryCategory();
        c.setTenantId(tenantId);
        c.setNameEn(name);
        c.setNameAr(name + " AR");
        c.setIconUrl("https://cdn.example/" + name + ".png");
        c.setSortOrder(sortOrder);
        PromoDirectoryCategory saved = categoryRepo.save(c);
        for (PromoBusiness b : linked) {
            linkRepo.save(PromoDirectoryLink.of(tenantId, saved.getId(), b.getId()));
        }
        return saved;
    }

    private void assertBusiness(JsonNode row, PromoBusiness b) {
        assertThat(row.get("id").asText()).isEqualTo(b.getId().toString());
        assertThat(row.get("nameEn").asText()).isEqualTo(b.getNameEn());
        assertThat(row.get("nameAr").asText()).isEqualTo(b.getNameAr());
        assertThat(row.get("logoUrl").asText()).isEqualTo(b.getLogoUrl());
        assertThat(row.get("descriptionEn").asText()).isEqualTo(b.getDescriptionEn());
        assertThat(row.get("descriptionAr").asText()).isEqualTo(b.getDescriptionAr());
        assertThat(row.get("websiteUrl").asText()).isEqualTo(b.getWebsiteUrl());
        assertThat(row.get("phoneE164").asText()).isEqualTo(b.getPhoneE164());
        assertThat(row.get("whatsappE164").asText()).isEqualTo(b.getWhatsappE164());
    }

    private List<String> offerIdsFor(User caller, UUID businessId) {
        Resp res = get(caller, "/api/v1/promotions/offers?businessId=" + businessId);
        assertThat(res.status()).isEqualTo(200);
        return ids(res.body());
    }

    private static List<String> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(n -> n.get("id").asText()).toList();
    }
}
