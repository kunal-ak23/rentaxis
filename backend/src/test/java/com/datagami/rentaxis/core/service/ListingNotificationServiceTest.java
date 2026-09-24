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
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    private User user(UUID tenantId, UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setRole(role);
        u.setEmail(role + "-" + UUID.randomUUID() + "@test.com");
        u.setName("User " + role);
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

        verify(notificationService, times(2)).notifyInAppInNewTx(
                eq(tenantId), any(UUID.class), eq("LISTING_AVAILABLE"), any(), any(), eq("LISTING"), eq(listingId), any());
        verify(interestRepository, times(2)).save(any(UnitListingInterest.class));
        assertThat(i1.getStatus()).isEqualTo(InterestStatus.NOTIFIED);
        assertThat(i1.getNotifiedAt()).isNotNull();
        assertThat(i2.getStatus()).isEqualTo(InterestStatus.NOTIFIED);
        assertThat(i2.getNotifiedAt()).isNotNull();
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

        verify(notificationService, never()).notifyInAppInNewTx(any(), any(), any(), any(), any(), any(), any(), any());
        verify(interestRepository, never()).save(any());
    }

    @Test
    void onListingPublished_failedNotify_leavesInterestActiveForRetry_andContinuesBatch() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID failingRenter = UUID.randomUUID();
        UUID healthyRenter = UUID.randomUUID();

        UnitListingInterest failing = interest(listingId, failingRenter, InterestStatus.ACTIVE);
        UnitListingInterest healthy = interest(listingId, healthyRenter, InterestStatus.ACTIVE);

        when(interestRepository.findByListingIdAndStatus(listingId, InterestStatus.ACTIVE))
                .thenReturn(List.of(failing, healthy));
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));
        doThrow(new RuntimeException("boom")).when(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(failingRenter), anyString(), any(), any(), anyString(), any(), any());

        service.onListingPublished(new ListingPublishedEvent(listingId, tenantId));

        // The failed interest must NOT be marked NOTIFIED — it stays ACTIVE so a
        // future publish retries the notification instead of suppressing it forever.
        assertThat(failing.getStatus()).isEqualTo(InterestStatus.ACTIVE);
        assertThat(failing.getNotifiedAt()).isNull();
        verify(interestRepository, never()).save(failing);

        // The rest of the batch still goes out and transitions to NOTIFIED.
        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(healthyRenter), eq("LISTING_AVAILABLE"), any(), any(), eq("LISTING"), eq(listingId), any());
        assertThat(healthy.getStatus()).isEqualTo(InterestStatus.NOTIFIED);
        assertThat(healthy.getNotifiedAt()).isNotNull();
        verify(interestRepository).save(healthy);
    }

    @Test
    void onListingPublished_missingListing_usesFallbackTitle() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renter1 = UUID.randomUUID();

        UnitListingInterest i1 = interest(listingId, renter1, InterestStatus.ACTIVE);
        when(interestRepository.findByListingIdAndStatus(listingId, InterestStatus.ACTIVE))
                .thenReturn(List.of(i1));
        // listingRepository.findById(listingId) unstubbed -> Optional.empty()
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        service.onListingPublished(new ListingPublishedEvent(listingId, tenantId));

        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(renter1), eq("LISTING_AVAILABLE"),
                anyString(), messageCaptor.capture(), eq("LISTING"), eq(listingId), any());
        assertThat(messageCaptor.getValue()).contains("A listing you wishlisted");
    }

    @Test
    void onInterestReceived_missingListing_usesFallbackTitle() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        User admin = user(tenantId, UserRole.TENANT_ADMIN);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin));
        // listingRepository.findById(listingId) unstubbed -> Optional.empty()
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        service.onInterestReceived(new InterestReceivedEvent(
                UUID.randomUUID(), listingId, UUID.randomUUID(), tenantId));

        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(admin.getId()), eq("LISTING_INTEREST_RECEIVED"),
                anyString(), messageCaptor.capture(), eq("LISTING"), eq(listingId), any());
        assertThat(messageCaptor.getValue()).contains("your listing");
    }

    @Test
    void onInterestReceived_notifiesAdminsAndPMsOnly() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterUserId = UUID.randomUUID();
        UUID interestId = UUID.randomUUID();

        User admin = user(tenantId, UserRole.TENANT_ADMIN);
        User pm = user(tenantId, UserRole.PROPERTY_MANAGER);
        User renter = user(tenantId, UserRole.RENTER);
        User tenantUser = user(tenantId, UserRole.TENANT_USER);

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin, pm, renter, tenantUser));

        service.onInterestReceived(new InterestReceivedEvent(interestId, listingId, renterUserId, tenantId));

        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(admin.getId()), eq("LISTING_INTEREST_RECEIVED"), any(), any(), eq("LISTING"), eq(listingId), any());
        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(pm.getId()), eq("LISTING_INTEREST_RECEIVED"), any(), any(), eq("LISTING"), eq(listingId), any());
        verify(notificationService, never()).notifyInAppInNewTx(
                eq(tenantId), eq(renter.getId()), anyString(), any(), any(), anyString(), any(), any());
        verify(notificationService, never()).notifyInAppInNewTx(
                eq(tenantId), eq(tenantUser.getId()), anyString(), any(), any(), anyString(), any(), any());
    }

    @Test
    void onInterestReceived_failedNotify_continuesToRemainingRecipients() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID interestId = UUID.randomUUID();

        User failingAdmin = user(tenantId, UserRole.TENANT_ADMIN);
        User healthyAdmin = user(tenantId, UserRole.TENANT_ADMIN);

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(failingAdmin, healthyAdmin));
        doThrow(new RuntimeException("boom")).when(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(failingAdmin.getId()), anyString(), any(), any(), anyString(), any(), any());

        service.onInterestReceived(new InterestReceivedEvent(interestId, listingId, UUID.randomUUID(), tenantId));

        verify(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(healthyAdmin.getId()), eq("LISTING_INTEREST_RECEIVED"), any(), any(), eq("LISTING"), eq(listingId), any());
    }
}
