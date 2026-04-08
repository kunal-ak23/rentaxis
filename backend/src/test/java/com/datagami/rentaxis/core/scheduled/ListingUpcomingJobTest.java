package com.datagami.rentaxis.core.scheduled;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ListingUpcomingJobTest {

    private LeaseRepository leaseRepository;
    private UnitListingRepository listingRepository;
    private ListingUpcomingJob job;

    @BeforeEach
    void setUp() {
        leaseRepository = mock(LeaseRepository.class);
        listingRepository = mock(UnitListingRepository.class);
        job = new ListingUpcomingJob(leaseRepository, listingRepository);
    }

    private Unit unit(UUID unitId) {
        Unit u = new Unit();
        u.setId(unitId);
        return u;
    }

    private Lease activeLease(UUID unitId, LocalDate endDate) {
        Lease l = new Lease();
        l.setId(UUID.randomUUID());
        l.setUnit(unit(unitId));
        l.setStatus(LeaseStatus.ACTIVE);
        l.setEndDate(endDate);
        return l;
    }

    private UnitListing listing(UUID unitId, ListingStatus status) {
        UnitListing l = new UnitListing();
        l.setId(UUID.randomUUID());
        l.setUnitId(unitId);
        l.setStatus(status);
        return l;
    }

    @Test
    void run_flipsUnlistedListingToUpcoming_whenLeaseEndingSoon() {
        UUID unitId = UUID.randomUUID();
        LocalDate leaseEnd = LocalDate.now().plusDays(15);
        Lease lease = activeLease(unitId, leaseEnd);
        UnitListing unlistedListing = listing(unitId, ListingStatus.UNLISTED);

        when(leaseRepository.findByStatusAndEndDateBetween(
                eq(LeaseStatus.ACTIVE), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(lease));
        when(listingRepository.findByUnitId(unitId)).thenReturn(Optional.of(unlistedListing));

        job.run();

        assertThat(unlistedListing.getStatus()).isEqualTo(ListingStatus.UPCOMING);
        assertThat(unlistedListing.getAvailableFrom()).isEqualTo(leaseEnd.plusDays(1));
        verify(listingRepository).save(unlistedListing);
    }

    @Test
    void run_doesNotFlipPublishedListing() {
        UUID unitId = UUID.randomUUID();
        LocalDate leaseEnd = LocalDate.now().plusDays(10);
        Lease lease = activeLease(unitId, leaseEnd);
        UnitListing publishedListing = listing(unitId, ListingStatus.PUBLISHED);

        when(leaseRepository.findByStatusAndEndDateBetween(
                eq(LeaseStatus.ACTIVE), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(lease));
        when(listingRepository.findByUnitId(unitId)).thenReturn(Optional.of(publishedListing));

        job.run();

        assertThat(publishedListing.getStatus()).isEqualTo(ListingStatus.PUBLISHED);
        verify(listingRepository, never()).save(any());
    }

    @Test
    void run_doesNotFlipWhenNoListingForUnit() {
        UUID unitId = UUID.randomUUID();
        LocalDate leaseEnd = LocalDate.now().plusDays(5);
        Lease lease = activeLease(unitId, leaseEnd);

        when(leaseRepository.findByStatusAndEndDateBetween(
                eq(LeaseStatus.ACTIVE), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(lease));
        when(listingRepository.findByUnitId(unitId)).thenReturn(Optional.empty());

        job.run();

        verify(listingRepository, never()).save(any());
    }
}
