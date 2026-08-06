package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.InterestReceivedEvent;
import com.datagami.rentaxis.core.event.ListingPublishedEvent;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins per-recipient transaction isolation for {@link ListingNotificationService}
 * against real Postgres.
 *
 * <p>The listener body runs under {@code REQUIRES_NEW}. When it called
 * {@link NotificationService#notify} (default {@code REQUIRED} propagation), each
 * call JOINED that single transaction: {@code Notification} ids are client-generated
 * ({@code GenerationType.UUID}), so {@code repository.save} is an in-memory persist
 * and the INSERT is deferred to commit-time flush. One recipient's constraint
 * violation therefore surfaced only at commit — after the per-recipient try/catch
 * had already completed — rolling back EVERY recipient's row. And even a
 * synchronous failure that the catch swallowed had already marked the shared
 * transaction rollback-only, so the method exited with
 * {@code UnexpectedRollbackException} and lost all rows anyway.
 *
 * <p>A pure-Mockito test cannot detect any of this: mocking
 * {@code NotificationService} bypasses the transaction interceptor entirely. This
 * IT drives the real beans and poisons exactly one recipient with a temporary
 * CHECK constraint on {@code notifications}, then asserts the other recipients'
 * rows survive and the failed interest is left ACTIVE for a future retry.
 */
@SpringBootTest
@Testcontainers
class ListingNotificationPerRecipientTxIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ListingNotificationService listingNotificationService;
    @Autowired LandlordOrgRepository landlordOrgRepository;
    @Autowired PropertyRepository propertyRepository;
    @Autowired UnitRepository unitRepository;
    @Autowired UnitListingRepository listingRepository;
    @Autowired UnitListingInterestRepository interestRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID listingId;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Listing-IT-Tenant-" + UUID.randomUUID());
        org = landlordOrgRepository.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        Property property = new Property();
        property.setNameEn("Listing-IT-Property");
        property.setEmirate(Emirate.DUBAI);
        property = propertyRepository.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("U-1");
        unit = unitRepository.save(unit);

        UnitListing listing = new UnitListing();
        listing.setUnitId(unit.getId());
        listing.setStatus(ListingStatus.PUBLISHED);
        listing.setTitleEn("IT Listing");
        listing.setSlug("it-listing-" + UUID.randomUUID());
        listingId = listingRepository.save(listing).getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS tmp_block_recipient");
        TenantContextHolder.clear();
    }

    private UnitListingInterest activeInterest(UUID renterUserId) {
        UnitListingInterest i = new UnitListingInterest();
        i.setListingId(listingId);
        i.setRenterUserId(renterUserId);
        i.setStatus(InterestStatus.ACTIVE);
        return interestRepository.save(i);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setTenantId(tenantId);
        u.setRole(role);
        u.setEmail(role + "-" + UUID.randomUUID() + "@it.test");
        u.setName("IT " + role);
        u.setPasswordHash("hash");
        return userRepository.save(u);
    }

    /** Any INSERT into notifications for this user violates the CHECK — the "poisoned" recipient. */
    private void blockInsertsFor(UUID userId) {
        jdbcTemplate.execute(
                "ALTER TABLE notifications ADD CONSTRAINT tmp_block_recipient CHECK (user_id <> '" + userId + "')");
    }

    private long notificationCount(UUID userId, String type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?", Long.class, userId, type);
        return count != null ? count : -1;
    }

    private Map<String, Object> interestRow(UUID interestId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, notified_at FROM unit_listing_interests WHERE id = ?", interestId);
    }

    @Test
    void onListingPublished_oneFailingRecipient_othersKeepRows_failedInterestStaysActive() {
        UnitListingInterest ok1 = activeInterest(UUID.randomUUID());
        UnitListingInterest poisoned = activeInterest(UUID.randomUUID());
        UnitListingInterest ok2 = activeInterest(UUID.randomUUID());
        blockInsertsFor(poisoned.getRenterUserId());

        assertThatCode(() -> listingNotificationService.onListingPublished(
                new ListingPublishedEvent(listingId, tenantId)))
                .doesNotThrowAnyException();

        assertThat(notificationCount(ok1.getRenterUserId(), "LISTING_AVAILABLE"))
                .as("healthy recipient before the failure keeps its committed row")
                .isEqualTo(1);
        assertThat(notificationCount(ok2.getRenterUserId(), "LISTING_AVAILABLE"))
                .as("healthy recipient after the failure keeps its committed row")
                .isEqualTo(1);
        assertThat(notificationCount(poisoned.getRenterUserId(), "LISTING_AVAILABLE"))
                .as("only the poisoned recipient's row is rolled back")
                .isZero();

        Map<String, Object> failedInterest = interestRow(poisoned.getId());
        assertThat(failedInterest.get("status"))
                .as("a failed notification must not mark the interest NOTIFIED")
                .isEqualTo("ACTIVE");
        assertThat(failedInterest.get("notified_at")).isNull();
        assertThat(interestRow(ok1.getId()).get("status")).isEqualTo("NOTIFIED");
        assertThat(interestRow(ok2.getId()).get("status")).isEqualTo("NOTIFIED");
    }

    @Test
    void onInterestReceived_oneFailingAdmin_otherRecipientsKeepRows() {
        User admin = user(UserRole.TENANT_ADMIN);
        User poisonedAdmin = user(UserRole.TENANT_ADMIN);
        User pm = user(UserRole.PROPERTY_MANAGER);
        User renter = user(UserRole.RENTER);
        blockInsertsFor(poisonedAdmin.getId());

        assertThatCode(() -> listingNotificationService.onInterestReceived(
                new InterestReceivedEvent(UUID.randomUUID(), listingId, renter.getId(), tenantId)))
                .doesNotThrowAnyException();

        assertThat(notificationCount(admin.getId(), "LISTING_INTEREST_RECEIVED")).isEqualTo(1);
        assertThat(notificationCount(pm.getId(), "LISTING_INTEREST_RECEIVED")).isEqualTo(1);
        assertThat(notificationCount(poisonedAdmin.getId(), "LISTING_INTEREST_RECEIVED"))
                .as("only the poisoned admin's row is rolled back")
                .isZero();
        assertThat(notificationCount(renter.getId(), "LISTING_INTEREST_RECEIVED"))
                .as("the renter who expressed interest is never a recipient")
                .isZero();
    }
}
