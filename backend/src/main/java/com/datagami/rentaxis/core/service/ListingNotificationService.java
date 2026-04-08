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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingNotificationService {

    private final UnitListingRepository listingRepository;
    private final UnitListingInterestRepository interestRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /**
     * When a listing is published, notify all renters with ACTIVE interest.
     * Interests that are already NOTIFIED are skipped (dedupe guard).
     * Transitions interest status to NOTIFIED after sending.
     */
    @EventListener
    @Transactional
    public void onListingPublished(ListingPublishedEvent event) {
        log.info("ListingPublishedEvent received for listing {}", event.listingId());

        List<UnitListingInterest> activeInterests =
                interestRepository.findByListingIdAndStatus(event.listingId(), InterestStatus.ACTIVE);

        Optional<UnitListing> listingOpt = listingRepository.findById(event.listingId());
        String listingTitle = listingOpt.map(l -> l.getTitleEn() != null ? l.getTitleEn() : "A listing you wishlisted")
                .orElse("A listing you wishlisted");

        for (UnitListingInterest interest : activeInterests) {
            // Dedupe: skip if already NOTIFIED (defensive check — query already filters, but guard here too)
            if (interest.getStatus() == InterestStatus.NOTIFIED) {
                log.debug("Interest {} already NOTIFIED — skipping", interest.getId());
                continue;
            }

            try {
                notificationService.notify(
                        event.tenantId(),
                        interest.getRenterUserId(),
                        "LISTING_AVAILABLE",
                        "Listing now available",
                        listingTitle + " is now available for rent.",
                        "LISTING",
                        event.listingId()
                );
            } catch (Exception ex) {
                log.error("Failed to notify renter {} for listing {} — {}",
                        interest.getRenterUserId(), event.listingId(), ex.getMessage());
                // Continue — do not abort the transaction for remaining interests
            }

            interest.setStatus(InterestStatus.NOTIFIED);
            interest.setNotifiedAt(LocalDateTime.now());
            interestRepository.save(interest);
        }

        log.info("ListingPublishedEvent processed: notified {} renters for listing {}",
                activeInterests.size(), event.listingId());
    }

    /**
     * When a renter expresses interest, notify all TENANT_ADMIN users of that tenant.
     */
    @EventListener
    @Transactional
    public void onInterestReceived(InterestReceivedEvent event) {
        log.info("InterestReceivedEvent received for listing {}, renter {}",
                event.listingId(), event.renterUserId());

        Optional<UnitListing> listingOpt = listingRepository.findById(event.listingId());
        String listingTitle = listingOpt.map(l -> l.getTitleEn() != null ? l.getTitleEn() : "your listing")
                .orElse("your listing");

        List<User> tenantAdmins = userRepository.findByTenantId(event.tenantId())
                .stream()
                .filter(u -> u.getRole() == UserRole.TENANT_ADMIN)
                .toList();

        for (User admin : tenantAdmins) {
            try {
                notificationService.notify(
                        event.tenantId(),
                        admin.getId(),
                        "LISTING_INTEREST_RECEIVED",
                        "New wishlist on your listing",
                        "Someone has wishlisted " + listingTitle + ".",
                        "LISTING",
                        event.listingId()
                );
            } catch (Exception ex) {
                log.error("Failed to notify admin {} for interest on listing {} — {}",
                        admin.getId(), event.listingId(), ex.getMessage());
                // Continue — do not abort the transaction
            }
        }

        log.info("InterestReceivedEvent processed: notified {} admins for listing {}",
                tenantAdmins.size(), event.listingId());
    }
}
