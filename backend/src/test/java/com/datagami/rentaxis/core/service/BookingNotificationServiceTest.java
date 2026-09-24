package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookingNotificationServiceTest {

    private BookingRequestRepository bookingRepository;
    private PropertyAmenityRepository amenityRepository;
    private ParkingSpotRepository parkingSpotRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private BookingNotificationService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRequestRepository.class);
        amenityRepository = mock(PropertyAmenityRepository.class);
        parkingSpotRepository = mock(ParkingSpotRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        service = new BookingNotificationService(bookingRepository, amenityRepository,
                parkingSpotRepository, userRepository, notificationService);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private BookingRequest amenityBooking() {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setResourceType(BookingResourceType.AMENITY);
        b.setAmenityId(UUID.randomUUID());
        b.setRenterUserId(UUID.randomUUID());
        PropertyAmenity a = new PropertyAmenity();
        a.setId(b.getAmenityId());
        a.setNameEn("Gym");
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(amenityRepository.findById(b.getAmenityId())).thenReturn(Optional.of(a));
        return b;
    }

    private BookingRequest parkingBooking() {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setResourceType(BookingResourceType.PARKING_SPOT);
        b.setParkingSpotId(UUID.randomUUID());
        b.setRenterUserId(UUID.randomUUID());
        ParkingSpot s = new ParkingSpot();
        s.setId(b.getParkingSpotId());
        s.setSpotNumber("P1");
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(parkingSpotRepository.findById(b.getParkingSpotId())).thenReturn(Optional.of(s));
        return b;
    }

    @Test
    void onBookingRequested_notifiesAdminsAndPMsOnly() {
        BookingRequest b = amenityBooking();
        User admin = user(UserRole.TENANT_ADMIN);
        User pm = user(UserRole.PROPERTY_MANAGER);
        User renter = user(UserRole.RENTER);
        User tenantUser = user(UserRole.TENANT_USER);
        User guard = user(UserRole.SECURITY_GUARD);
        User superAdmin = user(UserRole.SUPER_ADMIN);
        when(userRepository.findByTenantId(tenantId)).thenReturn(
                List.of(admin, pm, renter, tenantUser, guard, superAdmin));

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(admin.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(pm.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
        verify(notificationService, never()).notifyInAppInNewTx(eq(tenantId), eq(renter.getId()), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(notificationService, never()).notifyInAppInNewTx(eq(tenantId), eq(tenantUser.getId()), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(notificationService, never()).notifyInAppInNewTx(eq(tenantId), eq(guard.getId()), anyString(),
                anyString(), anyString(), anyString(), any(), any());
        verify(notificationService, never()).notifyInAppInNewTx(eq(tenantId), eq(superAdmin.getId()), anyString(),
                anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void onBookingRequested_oneFailingRecipientDoesNotStopOthers() {
        BookingRequest b = amenityBooking();
        User admin1 = user(UserRole.TENANT_ADMIN);
        User admin2 = user(UserRole.TENANT_ADMIN);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin1, admin2));
        doThrow(new RuntimeException("boom")).when(notificationService).notifyInAppInNewTx(
                eq(tenantId), eq(admin1.getId()), anyString(), anyString(), anyString(), anyString(), any(), any());

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(admin2.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
    }

    @Test
    void onBookingRequested_missingBooking_noOps() {
        UUID bookingId = UUID.randomUUID();
        // bookingRepository.findById(bookingId) unstubbed -> Optional.empty()

        service.onBookingRequested(new BookingRequestedEvent(bookingId, tenantId));

        verifyNoInteractions(notificationService);
    }

    @Test
    void onBookingDecided_approved_notifiesRenterWithApprovedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.APPROVED));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_APPROVED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
    }

    @Test
    void onBookingDecided_rejected_notifiesRenterWithRejectedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.REJECTED));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_REJECTED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
    }

    @Test
    void onBookingDecided_released_notifiesRenterWithReleasedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.RELEASED));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_RELEASED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()), any());
    }

    @Test
    void onBookingDecided_cancelled_neverNotifies() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.CANCELLED));

        verifyNoInteractions(notificationService);
    }

    @Test
    void onBookingDecided_missingBooking_fallsBackToYourBookingMessage() {
        UUID bookingId = UUID.randomUUID();
        UUID renterUserId = UUID.randomUUID();
        // bookingRepository.findById(bookingId) unstubbed -> Optional.empty()
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        service.onBookingDecided(new BookingDecidedEvent(
                bookingId, tenantId, renterUserId, BookingRequestStatus.APPROVED));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(renterUserId), eq("BOOKING_APPROVED"),
                anyString(), messageCaptor.capture(), eq("BOOKING"), eq(bookingId), any());
        assertThat(messageCaptor.getValue()).contains("your booking");
    }

    @Test
    void onBookingDecided_parkingSpot_messageIncludesSpotNumber() {
        BookingRequest b = parkingBooking();
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.APPROVED));

        verify(notificationService).notifyInAppInNewTx(eq(tenantId), eq(b.getRenterUserId()), eq("BOOKING_APPROVED"),
                anyString(), messageCaptor.capture(), eq("BOOKING"), eq(b.getId()),
                // #81: the spot as values, so "parking spot" is worded in the reader's language.
                eq(com.datagami.rentaxis.core.notification.NotificationMessage.of("BOOKING_APPROVED",
                        "resourceType", "PARKING", "resourceName", "P1")));
        assertThat(messageCaptor.getValue()).contains("parking spot P1");
    }
}
