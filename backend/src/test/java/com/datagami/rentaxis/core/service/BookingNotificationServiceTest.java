package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void onBookingRequested_notifiesAdminsAndPMsOnly() {
        BookingRequest b = amenityBooking();
        User admin = user(UserRole.TENANT_ADMIN);
        User pm = user(UserRole.PROPERTY_MANAGER);
        User renter = user(UserRole.RENTER);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin, pm, renter));

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notify(eq(tenantId), eq(admin.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
        verify(notificationService).notify(eq(tenantId), eq(pm.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
        verify(notificationService, never()).notify(eq(tenantId), eq(renter.getId()), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void onBookingRequested_oneFailingRecipientDoesNotStopOthers() {
        BookingRequest b = amenityBooking();
        User admin1 = user(UserRole.TENANT_ADMIN);
        User admin2 = user(UserRole.TENANT_ADMIN);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin1, admin2));
        doThrow(new RuntimeException("boom")).when(notificationService).notify(
                eq(tenantId), eq(admin1.getId()), anyString(), anyString(), anyString(), anyString(), any());

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notify(eq(tenantId), eq(admin2.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }

    @Test
    void onBookingDecided_approved_notifiesRenterWithApprovedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.APPROVED));

        verify(notificationService).notify(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_APPROVED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }

    @Test
    void onBookingDecided_released_notifiesRenterWithReleasedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.RELEASED));

        verify(notificationService).notify(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_RELEASED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }
}
