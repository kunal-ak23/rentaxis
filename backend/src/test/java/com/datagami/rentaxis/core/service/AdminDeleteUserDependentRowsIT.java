package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The ADMIN delete path, against a real database, for a renter with history.
 *
 * <p>{@code AccountDeletionDependentRowsIT} covers the in-app "delete my
 * account" flow. That flow worked because {@link AccountDeletionService} cleared
 * every reference inline before calling {@code deleteUser}. The admin endpoint
 * {@code DELETE /api/admin/users/&#123;id&#125;} calls {@link UserService#deleteUser}
 * directly, so it skipped all of that and returned a bare 500 for the very same
 * user — anyone with a lease, a gate pass, a booking, or a single promo-ad
 * impression, which is any renter who has opened the app.</p>
 *
 * <p>The clearing now lives in {@link UserReferenceReleaser} inside
 * {@code deleteUser}, so there is one description of how to delete a user
 * safely. These tests pin the admin path specifically: a green
 * AccountDeletionService suite said nothing about it.</p>
 */
@SpringBootTest
@Testcontainers
class AdminDeleteUserDependentRowsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired RenterRepository renterRepository;
    @Autowired PromoAdEventRepository promoAdEventRepository;
    @Autowired com.datagami.rentaxis.domain.repository.PromoAdRepository promoAdRepository;
    @Autowired com.datagami.rentaxis.domain.repository.PromoBusinessRepository promoBusinessRepository;
    @Autowired LandlordOrgRepository landlordOrgRepository;

    private UUID tenantId;
    private UUID adId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("AdminDelete-IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        com.datagami.rentaxis.domain.entity.PromoBusiness business =
                new com.datagami.rentaxis.domain.entity.PromoBusiness();
        business.setTenantId(tenantId);
        business.setNameEn("AdminDelete-IT-Business");
        business = promoBusinessRepository.save(business);

        com.datagami.rentaxis.domain.entity.PromoAd ad = new com.datagami.rentaxis.domain.entity.PromoAd();
        ad.setTenantId(tenantId);
        ad.setBusinessId(business.getId());
        ad.setTitleEn("AdminDelete-IT-Ad");
        adId = promoAdRepository.save(ad).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private User renterUser() {
        User u = new User();
        u.setEmail("admin-del-" + UUID.randomUUID() + "@example.com");
        u.setName("Admin Delete IT Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setTenantId(tenantId);
        u.setPasswordHash("x");
        return userRepository.save(u);
    }

    private PromoAdEvent impression(UUID userId) {
        Instant now = Instant.now();
        PromoAdEvent e = new PromoAdEvent();
        e.setTenantId(tenantId);
        e.setAdId(adId);
        e.setRenterUserId(userId);
        e.setEventType(com.datagami.rentaxis.domain.entity.enums.PromoEventType.IMPRESSION);
        e.setOccurredAt(now);
        // ck_promo_ad_event_day compares against Asia/Dubai, not the JVM zone.
        e.setDay(LocalDate.ofInstant(now, ZoneId.of("Asia/Dubai")));
        return promoAdEventRepository.save(e);
    }

    @Test
    void adminCanDeleteARenterWhoHasSeenAnAd() {
        User user = renterUser();
        impression(user.getId());

        // Before the fix this threw DataIntegrityViolationException on
        // fk_pae_user and reached the admin as a bare 500.
        assertThatCode(() -> userService.deleteUser(user.getId()))
                .doesNotThrowAnyException();

        assertThat(userRepository.findById(user.getId())).isEmpty();
        assertThat(promoAdEventRepository.findAll().stream()
                .anyMatch(e -> user.getId().equals(e.getRenterUserId())))
                .as("the renter's impressions go with the account")
                .isFalse();
    }

    /**
     * renters.user_id is ON DELETE CASCADE, so deleting the login row without
     * detaching first would cascade the renter away and then trip
     * leases.renter_id. The renter — and therefore any lease and payment history
     * hanging off it — has to survive.
     */
    @Test
    void deletingTheLoginRowKeepsTheRenterRecord() {
        User user = renterUser();

        Renter renter = new Renter();
        renter.setTenantId(tenantId);
        renter.setNameEn("Admin Delete IT Renter");
        renter.setUserId(user.getId());
        renter = renterRepository.save(renter);
        UUID renterId = renter.getId();

        userService.deleteUser(user.getId());

        assertThat(userRepository.findById(user.getId())).isEmpty();
        Renter surviving = renterRepository.findById(renterId).orElse(null);
        assertThat(surviving).as("renter must outlive the login row").isNotNull();
        assertThat(surviving.getUserId()).as("but no longer point at a deleted user").isNull();
    }

    /** A user with no history at all must still delete cleanly. */
    @Test
    void adminCanDeleteAUserWithNoHistory() {
        User user = renterUser();

        assertThatCode(() -> userService.deleteUser(user.getId()))
                .doesNotThrowAnyException();
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }
}
