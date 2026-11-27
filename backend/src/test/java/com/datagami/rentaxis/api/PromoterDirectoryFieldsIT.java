package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Business directory (spec 2026-11-26): a promoter's business edit goes live at once,
 * so a promoter cannot set a business's description or website. Only admins can.
 */
class PromoterDirectoryFieldsIT extends AbstractPromoterIT {

    private UUID org;
    private User admin;
    private PromoBusiness wash;
    private User promoter;

    @BeforeEach
    void setUp() {
        org = org();
        admin = user(org, UserRole.TENANT_ADMIN);
        wash = business(org, "QuickWash");
        promoter = promoter(org, true, List.of(wash.getId()), List.of());
    }

    @Test
    void promoterUpdate_keepsDescriptionAndWebsite() {
        Resp set = put(admin, "/api/v1/promotions/businesses/" + wash.getId(), body(
                "nameEn", wash.getNameEn(),
                "allowedDomains", List.of("quickwash.ae", "evil.example"),
                "descriptionEn", "Car wash at your door",
                "descriptionAr", "غسيل سيارات",
                "websiteUrl", "https://quickwash.ae/book"));
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.text("websiteUrl")).isEqualTo("https://quickwash.ae/book");

        Resp edit = put(promoter, "/api/v1/promoter/businesses/" + wash.getId(), body(
                "nameEn", wash.getNameEn(),
                "descriptionEn", "Promoter's own words",
                "descriptionAr", null,
                "websiteUrl", "https://evil.example/landing"));
        assertThat(edit.status()).isEqualTo(200);
        assertThat(edit.text("descriptionEn")).isEqualTo("Car wash at your door");
        assertThat(edit.text("websiteUrl")).isEqualTo("https://quickwash.ae/book");

        JsonNode listed = promoterRow(promoter, wash.getId());
        assertThat(listed.get("descriptionEn").asText()).isEqualTo("Car wash at your door");
        assertThat(listed.get("descriptionAr").asText()).isEqualTo("غسيل سيارات");
        assertThat(listed.get("websiteUrl").asText()).isEqualTo("https://quickwash.ae/book");

        PromoBusiness stored = businessRepo.findById(wash.getId()).orElseThrow();
        assertThat(stored.getDescriptionEn()).isEqualTo("Car wash at your door");
        assertThat(stored.getDescriptionAr()).isEqualTo("غسيل سيارات");
        assertThat(stored.getWebsiteUrl()).isEqualTo("https://quickwash.ae/book");
    }

    @Test
    void narrowingDomainsBelowTheWebsite_isRefusedWithWordingEachRoleCanActOn() {
        put(admin, "/api/v1/promotions/businesses/" + wash.getId(), body(
                "nameEn", wash.getNameEn(),
                "allowedDomains", List.of("quickwash.ae"),
                "websiteUrl", "https://quickwash.ae/book"));

        Resp promoterEdit = put(promoter, "/api/v1/promoter/businesses/" + wash.getId(), body(
                "nameEn", wash.getNameEn(),
                "allowedDomains", List.of("other.example")));
        assertThat(promoterEdit.status()).isEqualTo(400);
        assertThat(promoterEdit.body().toString())
                .contains("organisation admin to change the website first")
                .doesNotContain("must be an https link");

        Resp adminEdit = put(admin, "/api/v1/promotions/businesses/" + wash.getId(), body(
                "nameEn", wash.getNameEn(),
                "allowedDomains", List.of("other.example"),
                "websiteUrl", "https://quickwash.ae/book"));
        assertThat(adminEdit.status()).isEqualTo(400);
        assertThat(adminEdit.body().toString())
                .contains("The website must be an https link on one of this business's allowed domains.");
    }

    @Test
    void promoterCreate_ignoresDescriptionAndWebsite() {
        Resp create = post(promoter, "/api/v1/promoter/businesses", body(
                "nameEn", "Promoter Spa " + UUID.randomUUID().toString().substring(0, 6),
                "allowedDomains", List.of("spa.example"),
                "descriptionEn", "Best spa",
                "descriptionAr", "أفضل سبا",
                "websiteUrl", "https://spa.example"));
        assertThat(create.status()).isEqualTo(201);
        assertThat(create.text("descriptionEn")).isNull();
        assertThat(create.text("websiteUrl")).isNull();

        PromoBusiness stored = businessRepo.findById(UUID.fromString(create.text("id"))).orElseThrow();
        assertThat(stored.getDescriptionEn()).isNull();
        assertThat(stored.getDescriptionAr()).isNull();
        assertThat(stored.getWebsiteUrl()).isNull();
    }

    private JsonNode promoterRow(User caller, UUID businessId) {
        Resp res = get(caller, "/api/v1/promoter/businesses");
        assertThat(res.status()).isEqualTo(200);
        return StreamSupport.stream(res.body().spliterator(), false)
                .filter(n -> n.get("id").asText().equals(businessId.toString()))
                .findFirst().orElseThrow();
    }
}
