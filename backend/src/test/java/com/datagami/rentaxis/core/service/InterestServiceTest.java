package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.ListingNotAvailableException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.InterestReceivedEvent;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterestServiceTest {

    private UnitListingRepository listingRepository;
    private UnitListingInterestRepository interestRepository;
    private ApplicationEventPublisher eventPublisher;
    private InterestService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(UnitListingRepository.class);
        interestRepository = mock(UnitListingInterestRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new InterestService(listingRepository, interestRepository, eventPublisher);
    }

    private UnitListing listing(UUID tenantId, UUID listingId) {
        return listing(tenantId, listingId, ListingStatus.PUBLISHED);
    }

    private UnitListing listing(UUID tenantId, UUID listingId, ListingStatus status) {
        UnitListing l = new UnitListing();
        l.setId(listingId);
        l.setTenantId(tenantId);
        l.setStatus(status);
        return l;
    }

    @Test
    void addInterest_isIdempotent() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));

        UnitListingInterest existing = new UnitListingInterest();
        existing.setId(UUID.randomUUID());
        existing.setListingId(listingId);
        existing.setRenterUserId(renterId);
        existing.setStatus(InterestStatus.ACTIVE);

        when(interestRepository.findByListingIdAndRenterUserId(listingId, renterId))
                .thenReturn(Optional.of(existing));

        UnitListingInterest result = service.addInterest(tenantId, listingId, renterId, null);

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(interestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addInterest_publishesEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();

        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantId, listingId)));
        when(interestRepository.findByListingIdAndRenterUserId(listingId, renterId)).thenReturn(Optional.empty());
        when(interestRepository.save(any(UnitListingInterest.class))).thenAnswer(inv -> {
            UnitListingInterest i = inv.getArgument(0);
            if (i.getId() == null) i.setId(UUID.randomUUID());
            return i;
        });

        service.addInterest(tenantId, listingId, renterId, "Interested!");

        ArgumentCaptor<InterestReceivedEvent> captor = ArgumentCaptor.forClass(InterestReceivedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().listingId()).isEqualTo(listingId);
        assertThat(captor.getValue().renterUserId()).isEqualTo(renterId);
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void withdraw_setsStatusWithdrawn() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();

        UnitListingInterest interest = new UnitListingInterest();
        interest.setId(UUID.randomUUID());
        interest.setTenantId(tenantId);
        interest.setListingId(listingId);
        interest.setRenterUserId(renterId);
        interest.setStatus(InterestStatus.ACTIVE);

        when(interestRepository.findByListingIdAndRenterUserId(listingId, renterId))
                .thenReturn(Optional.of(interest));

        service.withdraw(tenantId, listingId, renterId);

        assertThat(interest.getStatus()).isEqualTo(InterestStatus.WITHDRAWN);
        verify(interestRepository).save(interest);
    }

    @Test
    void addInterest_rejectsDraftListing() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();
        when(listingRepository.findById(listingId))
                .thenReturn(Optional.of(listing(tenantId, listingId, ListingStatus.DRAFT)));

        assertThatThrownBy(() -> service.addInterest(tenantId, listingId, renterId, null))
                .isInstanceOf(ListingNotAvailableException.class)
                .hasMessageContaining("not available");
        verify(interestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void addInterest_rejectsUnlistedListing() {
        UUID tenantId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();
        when(listingRepository.findById(listingId))
                .thenReturn(Optional.of(listing(tenantId, listingId, ListingStatus.UNLISTED)));

        assertThatThrownBy(() -> service.addInterest(tenantId, listingId, renterId, null))
                .isInstanceOf(ListingNotAvailableException.class);
        verify(interestRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void listForListing_throwsWhenWrongTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        // Listing belongs to tenantA
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing(tenantA, listingId)));

        assertThatThrownBy(() -> service.listForListing(tenantB, listingId))
                .isInstanceOf(NotFoundException.class);
    }
}
