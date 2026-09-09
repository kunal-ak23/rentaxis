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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Account deletion against a real database, with the dependent rows a real
 * renter accumulates.
 *
 * <p>This exists because the unit tests could not have caught the bug it pins.
 * {@code AccountDeletionServiceTest} mocks {@link UserService}, so
 * {@code deleteUser} never meets a foreign key: the suite stayed green while
 * deletion failed for anyone who had used the app. Five tables reference
 * {@code users(id)} as NOT NULL with no {@code ON DELETE} clause, and
 * {@code PromotionFeedService} writes one impression row per ad, per renter, per
 * Dubai day — so the offers strip on the renter home screen was enough to make
 * the account undeletable.
 *
 * <p>That matters beyond tidiness: in-app deletion is App Store Review Guideline
 * 5.1.1(v), and it is the reviewer's own tap that would have hit the 500.
 *
 * <p>Only {@code promo_ad_events} is exercised here rather than all five tables.
 * It is the one every renter has, it is the cheapest to seed honestly, and the
 * failure mode is identical for the others — the point is to prove the delete
 * reaches the login row with dependants present, not to enumerate the schema.
 */
@SpringBootTest
@Testcontainers
class AccountDeletionDependentRowsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired AccountDeletionService accountDeletionService;
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
        org.setName("Deletion-IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        // promo_ad_events.ad_id is NOT NULL, so an impression needs a real ad
        // behind it — same shape production writes.
        com.datagami.rentaxis.domain.entity.PromoBusiness business =
                new com.datagami.rentaxis.domain.entity.PromoBusiness();
        business.setTenantId(tenantId);
        business.setNameEn("Deletion-IT-Business");
        business = promoBusinessRepository.save(business);

        com.datagami.rentaxis.domain.entity.PromoAd ad = new com.datagami.rentaxis.domain.entity.PromoAd();
        ad.setTenantId(tenantId);
        ad.setBusinessId(business.getId());
        ad.setTitleEn("Deletion-IT-Ad");
        adId = promoAdRepository.save(ad).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private User renterUser() {
        User u = new User();
        u.setEmail("renter-" + UUID.randomUUID() + "@example.com");
        u.setName("Deletion IT Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setTenantId(tenantId);
        u.setPasswordHash("x");
        return userRepository.save(u);
    }

    /** One impression row — what opening the app and seeing the offers strip produces. */
    private PromoAdEvent impression(UUID userId) {
        Instant now = Instant.now();

        PromoAdEvent e = new PromoAdEvent();
        e.setTenantId(tenantId);
        e.setAdId(adId);
        e.setRenterUserId(userId);
        e.setEventType(com.datagami.rentaxis.domain.entity.enums.PromoEventType.IMPRESSION);
        e.setOccurredAt(now);
        // ck_promo_ad_event_day requires day = (occurred_at AT TIME ZONE
        // 'Asia/Dubai')::date, so the day has to be derived the way
        // PromotionFeedService derives it. LocalDate.now() happens to agree on a
        // machine near UTC+4 and disagrees on a UTC CI runner after 20:00, which
        // is precisely the drift the constraint exists to catch — and it caught
        // this test.
        e.setDay(LocalDate.ofInstant(now, java.time.ZoneId.of("Asia/Dubai")));
        return promoAdEventRepository.save(e);
    }

    @Test
    void deletesAnAccountThatHasPromotionImpressions() {
        User user = renterUser();
        impression(user.getId());
        assertThat(promoAdEventRepository.findAll()).isNotEmpty();

        // Before the fix this threw DataIntegrityViolationException on
        // fk_pae_user and surfaced to the caller as a bare 500.
        assertThatCode(() -> accountDeletionService.deleteOwnAccount(user.getId()))
                .doesNotThrowAnyException();

        assertThat(userRepository.findById(user.getId())).isEmpty();
    }

    @Test
    void removesTheUsersOwnPromotionActivityWithTheAccount() {
        User user = renterUser();
        impression(user.getId());

        accountDeletionService.deleteOwnAccount(user.getId());

        // The impression history is the person's own activity, not a business
        // record, so it goes with them rather than being retained unlinked.
        assertThat(promoAdEventRepository.findAll())
                .noneMatch(e -> user.getId().equals(e.getRenterUserId()));
    }

    @Test
    void leavesAnotherUsersActivityAlone() {
        User doomed = renterUser();
        User bystander = renterUser();
        impression(doomed.getId());
        impression(bystander.getId());

        accountDeletionService.deleteOwnAccount(doomed.getId());

        // The delete is keyed on the user, so a shared table must not be swept.
        assertThat(promoAdEventRepository.findAll())
                .anyMatch(e -> bystander.getId().equals(e.getRenterUserId()));
        assertThat(userRepository.findById(bystander.getId())).isPresent();
    }

    @Test
    void keepsTheRenterRecordThatEveryLeaseHangsOff() {
        User user = renterUser();
        Renter renter = new Renter();
        renter.setTenantId(tenantId);
        renter.setNameEn("Deletion IT Renter");
        renter.setUserId(user.getId());
        renter = renterRepository.save(renter);
        impression(user.getId());

        accountDeletionService.deleteOwnAccount(user.getId());

        // renters.user_id is ON DELETE CASCADE (changeset 57): without the
        // detach, deleting the login row would take the renter — and with it
        // every lease and cheque that references them.
        Renter survivor = renterRepository.findById(renter.getId()).orElse(null);
        assertThat(survivor).isNotNull();
        assertThat(survivor.getUserId()).isNull();
    }

    @Test
    void deletingAnAccountWithNoActivityStillWorks() {
        User user = renterUser();

        assertThatCode(() -> accountDeletionService.deleteOwnAccount(user.getId()))
                .doesNotThrowAnyException();
        assertThat(userRepository.findById(user.getId())).isEmpty();
    }
}
