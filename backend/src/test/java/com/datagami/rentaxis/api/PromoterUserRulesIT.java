package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.UserTenantMembershipRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Promoter plan, "Role plumbing": a promoter is made only from Promotions →
 * Promoters, lives in exactly one organisation, and never enters
 * user_property_assignments. The Users surface keeps listing, deactivating and
 * reactivating promoters.
 */
class PromoterUserRulesIT extends AbstractPromoterIT {

    @Autowired UserService userService;
    @Autowired UserTenantMembershipRepository membershipRepo;

    @Test
    void usersCannotCreateAPromoter() {
        UUID org = org();
        User admin = user(org, UserRole.TENANT_ADMIN);

        Resp res = post(admin, "/api/admin/users", body(
                "email", "promoter-" + UUID.randomUUID() + "@example.test",
                "name", "Laundry Co", "role", "PROMOTER"));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.text("message")).isEqualTo("Promoters are invited from Promotions → Promoters");
        assertThat(res.text("code")).isEqualTo("user.promoterRole");
    }

    @Test
    void noRoleChangeToOrFromPromoter() {
        UUID org = org();
        User admin = user(org, UserRole.TENANT_ADMIN);
        User staff = user(org, UserRole.TENANT_USER);
        User promoter = promoter(org, false, List.of(), List.of());

        assertThat(put(admin, "/api/admin/users/" + staff.getId(), body("role", "PROMOTER")).status())
                .isEqualTo(400);
        assertThat(put(admin, "/api/admin/users/" + promoter.getId(), body("role", "TENANT_USER")).status())
                .isEqualTo(400);
        assertThat(userRepo.findById(staff.getId()).orElseThrow().getRole()).isEqualTo(UserRole.TENANT_USER);
        assertThat(userRepo.findById(promoter.getId()).orElseThrow().getRole()).isEqualTo(UserRole.PROMOTER);

        // Editing a promoter's own details still works.
        Resp renamed = put(admin, "/api/admin/users/" + promoter.getId(), body("name", "Sparkle Car Wash"));
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(userRepo.findById(promoter.getId()).orElseThrow().getName()).isEqualTo("Sparkle Car Wash");
    }

    @Test
    void aSuperAdminCannotMoveAPromoterToAnotherOrganisation() {
        UUID org = org();
        UUID other = org();
        User superAdmin = user(null, UserRole.SUPER_ADMIN);
        User promoter = promoter(org, false, List.of(), List.of());

        Resp res = put(superAdmin, "/api/admin/users/" + promoter.getId(), body("tenantId", other.toString()));

        assertThat(res.status()).isEqualTo(400);
        assertThat(userRepo.findById(promoter.getId()).orElseThrow().getTenantId()).isEqualTo(org);
    }

    @Test
    void aPromoterNeverGetsAMembershipOutsideItsHomeOrganisation() {
        UUID org = org();
        UUID other = org();
        User promoter = promoter(org, false, List.of(), List.of());

        assertThatThrownBy(() -> userService.addTenantMembership(promoter.getId(), other))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(membershipRepo.existsByUserIdAndTenantId(promoter.getId(), other)).isFalse();

        // Its home organisation is fine (createUser adds exactly that one).
        userService.addTenantMembership(promoter.getId(), org);
        assertThat(membershipRepo.existsByUserIdAndTenantId(promoter.getId(), org)).isTrue();
    }

    @Test
    void aPromoterIsNeverAssignedAPropertyThroughUsers() {
        UUID org = org();
        User admin = user(org, UserRole.TENANT_ADMIN);
        Property tower = property(org, "Tower");
        User promoter = promoter(org, false, List.of(), List.of());

        Resp res = post(admin, "/api/admin/users/" + promoter.getId() + "/properties/" + tower.getId(), null);

        assertThat(res.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_property_assignments WHERE user_id = ?", Integer.class, promoter.getId()))
                .isZero();
    }

    @Test
    void theUsersSurfaceStillListsDeactivatesAndReactivatesPromoters() {
        UUID org = org();
        User admin = user(org, UserRole.TENANT_ADMIN);
        User promoter = promoter(org, false, List.of(), List.of());

        Resp list = get(admin, "/api/admin/users");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().findValuesAsText("id")).contains(promoter.getId().toString());

        assertThat(post(admin, "/api/admin/users/" + promoter.getId() + "/deactivate", null).status()).isEqualTo(200);
        assertThat(userRepo.findById(promoter.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(post(admin, "/api/admin/users/" + promoter.getId() + "/reactivate", null).status()).isEqualTo(200);
        assertThat(userRepo.findById(promoter.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void existingAdsStayLiveAndTheApprovalColumnRefusesUnknownStates() {
        UUID org = org();
        var biz = business(org, "Spice");
        UUID adId = UUID.randomUUID();
        // Written the way a pre-183 row looks: no approval column named.
        jdbc.update("INSERT INTO promo_ads (id, tenant_id, business_id, title_en) VALUES (?, ?, ?, ?)",
                adId, org, biz.getId(), "Brunch");
        assertThat(jdbc.queryForObject("SELECT approval_status FROM promo_ads WHERE id = ?", String.class, adId))
                .isEqualTo("APPROVED");
        assertThatThrownBy(() -> jdbc.update("UPDATE promo_ads SET approval_status = 'LIVE' WHERE id = ?", adId))
                .hasMessageContaining("ck_promo_ad_approval_status");
    }
}
