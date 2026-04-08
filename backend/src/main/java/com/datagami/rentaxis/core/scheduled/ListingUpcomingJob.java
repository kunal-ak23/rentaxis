package com.datagami.rentaxis.core.scheduled;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Nightly job (02:00 server time) that promotes listings to UPCOMING status
 * when the current lease on a unit is ending within 30 days.
 *
 * Transitions: DRAFT → UPCOMING, UNLISTED → UPCOMING.
 * Already PUBLISHED listings are left untouched.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingUpcomingJob {

    static final int UPCOMING_WINDOW_DAYS = 30;

    private final LeaseRepository leaseRepository;
    private final UnitListingRepository listingRepository;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void run() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(UPCOMING_WINDOW_DAYS);

        log.info("ListingUpcomingJob starting — checking leases ending between {} and {}", today, horizon);

        // Step 1: re-check existing UPCOMING listings. If the lease was
        // extended past the horizon (or no longer exists), revert to DRAFT;
        // otherwise refresh availableFrom in case the lease end date moved.
        int reverted = 0;
        int refreshed = 0;
        for (UnitListing listing : listingRepository.findByStatus(ListingStatus.UPCOMING)) {
            if (listing.getUnitId() == null) {
                continue;
            }
            Optional<Lease> currentLeaseOpt = leaseRepository
                    .findByStatusAndEndDateBetween(LeaseStatus.ACTIVE, today, horizon)
                    .stream()
                    .filter(l -> l.getUnit() != null
                            && java.util.Objects.equals(l.getUnit().getId(), listing.getUnitId()))
                    .findFirst();
            if (currentLeaseOpt.isEmpty()) {
                listing.setStatus(ListingStatus.DRAFT);
                listingRepository.save(listing);
                reverted++;
                log.info("Listing {} reverted UPCOMING -> DRAFT (lease no longer ending in window)",
                        listing.getId());
            } else {
                LocalDate newAvailable = currentLeaseOpt.get().getEndDate().plusDays(1);
                if (!newAvailable.equals(listing.getAvailableFrom())) {
                    listing.setAvailableFrom(newAvailable);
                    listingRepository.save(listing);
                    refreshed++;
                }
            }
        }

        List<Lease> expiringLeases = leaseRepository.findByStatusAndEndDateBetween(
                LeaseStatus.ACTIVE, today, horizon);

        int promoted = 0;
        for (Lease lease : expiringLeases) {
            if (lease.getUnit() == null) {
                continue;
            }
            UUID unitId = lease.getUnit().getId();
            Optional<UnitListing> listingOpt = listingRepository.findByUnitId(unitId);
            if (listingOpt.isEmpty()) {
                log.debug("No listing for unit {} — skipping", unitId);
                continue;
            }

            UnitListing listing = listingOpt.get();
            ListingStatus current = listing.getStatus();

            if (current == ListingStatus.DRAFT || current == ListingStatus.UNLISTED) {
                listing.setStatus(ListingStatus.UPCOMING);
                listing.setAvailableFrom(lease.getEndDate().plusDays(1));
                listingRepository.save(listing);
                promoted++;
                log.info("Listing {} promoted to UPCOMING (was {}) — availableFrom {}",
                        listing.getId(), current, listing.getAvailableFrom());
            } else {
                log.debug("Listing {} has status {} — no transition needed", listing.getId(), current);
            }
        }

        log.info("ListingUpcomingJob finished — promoted {}, refreshed {}, reverted {}",
                promoted, refreshed, reverted);
    }
}
