package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.InterestReceivedEvent;
import com.datagami.rentaxis.core.event.ListingPublishedEvent;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ListingNotificationServiceTest {

    private UnitListingRepository listingRepository;
    private UnitListingInterestRepository interestRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private ListingNotificationService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(UnitListingRepository.class);
        interestRepository = mock(UnitListingInterestRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        service = new ListingNotificationService(listingRepository, interestRepository, userRepository, notificationService);
    }

    private UnitListing listing(UUID tenantId, UUID listingId) {
        UnitListing l = new UnitListing();
        l.setId(listingId);
        l.setTenantId(tenantId);
        l.setTitleEn("Nice Apartment");
        return l;
    }

    private UnitListingInterest interest(UUID listingId, UUID renterUserId, InterestStatus status) {
        UnitListingInterest i = new UnitListingInterest();
        i.setId(UUID.randomUUID());
        i.setListingId(listingId);
        i.setRenterUserId(renterUserId);
        i.setStatus(status);
        return i;
    }

    private User adminUser(UUID tenantId) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setRole(UserRole.TENANT_ADMIN);
        u.setEmail("admin@test.com");
        u.setName("Admin");
        u.setPasswordHash("hash");
        return u;
    }

    @Test
    void onListingPublished_notifiesActiveInterests_andMarksNotified() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renter1 = UUID.randomUUID();
        UUID renter2 = UUID.randomUUID();

        UnitListingInterest i1 = interest(listingId, renter1, InterestStatus.ACTIVE);
        UnitListingInterest i2 = interest(listingId, renter2, InterestStatus.ACTIVE);

        when(interestRepository.findByListingIdAndStatus(listingId, InterestStatus.ACTIVE))
                .thenReturn(List.of(i1, i2));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));

        service.onListingPublished(new ListingPublishedEvent(listingId, tenantId));

        verify(notificationService, times(2)).notify(
                eq(tenantId), any(UUID.class), eq("LISTING_AVAILABLE"), any(), any(), eq("LISTING"), eq(listingId));
        verify(interestRepository, times(2)).save(any(UnitListingInterest.class));
        assertThat(i1.getStatus()).isEqualTo(InterestStatus.NOTIFIED);
        assertThat(i2.getStatus()).isEqualTo(InterestStatus.NOTIFIED);
    }

    @Test
    void onListingPublished_skipsAlreadyNotifiedInterests() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renter1 = UUID.randomUUID();

        // The query returns only ACTIVE, but we simulate the dedupe guard by setting NOTIFIED directly
        UnitListingInterest alreadyNotified = interest(listingId, renter1, InterestStatus.NOTIFIED);

        when(interestRepository.findByListingIdAndStatus(listingId, InterestStatus.ACTIVE))
                .thenReturn(List.of(alreadyNotified));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));

        service.onListingPublished(new ListingPublishedEvent(listingId, tenantId));

        verify(notificationService, never()).notify(any(), any(), any(), any(), any(), any(), any());
        verify(interestRepository, never()).save(any());
    }

    @Test
    void onInterestReceived_notifiesLandlord() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterUserId = UUID.randomUUID();
        UUID interestId = UUID.randomUUID();

        User admin = adminUser(tenantId);

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin));

        service.onInterestReceived(new InterestReceivedEvent(interestId, listingId, renterUserId, tenantId));

        verify(notificationService).notify(
                eq(tenantId),
                eq(admin.getId()),
                eq("LISTING_INTEREST_RECEIVED"),
                any(),
                any(),
                eq("LISTING"),
                eq(listingId)
        );
    }
}
