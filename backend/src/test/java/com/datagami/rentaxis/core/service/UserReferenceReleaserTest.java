package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Clearing every reference to a user before the user row is deleted.
 *
 * <p>These assertions used to live in {@code AccountDeletionServiceTest},
 * because the logic used to live in {@code AccountDeletionService}. That is
 * exactly why the admin delete path was broken: the in-app flow had the
 * clearing and a green test suite, while {@code UserService.deleteUser} — what
 * {@code DELETE /api/admin/users/&#123;id&#125;} actually calls — went straight
 * to {@code deleteById} and hit a foreign key.</p>
 */
class UserReferenceReleaserTest {

    private RenterRepository renterRepository;
    private PromoAdEventRepository promoAdEventRepository;
    private BookingRequestRepository bookingRequestRepository;
    private GatePassRepository gatePassRepository;
    private GatePassScanRepository gatePassScanRepository;
    private LeaseInteractionRepository leaseInteractionRepository;
    private DeviceTokenRepository deviceTokenRepository;
    private NotificationRepository notificationRepository;
    private GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private UserReferenceReleaser releaser;

    @BeforeEach
    void setUp() {
        renterRepository = mock(RenterRepository.class);
        promoAdEventRepository = mock(PromoAdEventRepository.class);
        bookingRequestRepository = mock(BookingRequestRepository.class);
        gatePassRepository = mock(GatePassRepository.class);
        gatePassScanRepository = mock(GatePassScanRepository.class);
        leaseInteractionRepository = mock(LeaseInteractionRepository.class);
        deviceTokenRepository = mock(DeviceTokenRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        guardPropertyAssignmentRepository = mock(GuardPropertyAssignmentRepository.class);

        releaser = new UserReferenceReleaser(renterRepository, promoAdEventRepository,
                bookingRequestRepository, gatePassRepository, gatePassScanRepository,
                leaseInteractionRepository, deviceTokenRepository, notificationRepository,
                guardPropertyAssignmentRepository);

        when(renterRepository.findByUserId(any())).thenReturn(Optional.empty());
    }

    /**
     * renters.user_id is ON DELETE CASCADE, so leaving the link in place means
     * deleting the login cascades the tenancy record away — and then trips
     * leases.renter_id on the way out.
     */
    @Test
    void renterLoginIsDetachedRatherThanCascaded() {
        UUID userId = UUID.randomUUID();
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(userId);
        when(renterRepository.findByUserId(userId)).thenReturn(Optional.of(renter));

        releaser.release(userId);

        ArgumentCaptor<Renter> saved = ArgumentCaptor.forClass(Renter.class);
        verify(renterRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId())
                .as("the tenancy record survives with the personal link severed")
                .isNull();
    }

    @Test
    void personalActivityIsDeletedWithTheAccount() {
        UUID userId = UUID.randomUUID();

        releaser.release(userId);

        verify(promoAdEventRepository).deleteByRenterUserId(userId);
        verify(bookingRequestRepository).deleteByRenterUserId(userId);
        verify(deviceTokenRepository).deleteByUserId(userId);
        verify(notificationRepository).deleteByUserIdUnfiltered(userId);
        verify(guardPropertyAssignmentRepository).deleteByUserId(userId);
    }

    /**
     * The building's access log is a business record. It must outlive the
     * account, de-identified — not be deleted, and not block the deletion.
     */
    @Test
    void businessRecordsAreDetachedNotDeleted() {
        UUID userId = UUID.randomUUID();

        releaser.release(userId);

        verify(gatePassRepository).detachCreatedBy(userId);
        verify(gatePassScanRepository).detachScannedBy(userId);
        verify(leaseInteractionRepository).detachCreatedBy(userId);
    }

    @Test
    void aUserWithNoRenterRecordIsHandled() {
        UUID userId = UUID.randomUUID();

        releaser.release(userId);

        verify(renterRepository).findByUserId(userId);
        verify(renterRepository, org.mockito.Mockito.never()).save(any());
    }
}
