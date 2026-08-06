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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Turns listing lifecycle events into in-app
 * {@link com.datagami.rentaxis.domain.entity.Notification} rows.
 * {@code AFTER_COMMIT} so a listener only runs once the publishing transaction
 * has committed, and {@code REQUIRES_NEW} so the listener body runs in its own
 * transaction rather than piggybacking on one that is already gone.
 *
 * <p><b>Per-recipient failure isolation.</b> The obvious approach —
 * per-recipient try/catch around {@link NotificationService#notify} — does NOT
 * isolate failures: {@code notify} is {@code @Transactional} with default
 * ({@code REQUIRED}) propagation, so each call simply joins this listener's one
 * {@code REQUIRES_NEW} transaction instead of opening its own. Notification ids
 * are client-generated ({@code GenerationType.UUID}), so the INSERT is deferred
 * to commit-time flush: a row that violates a DB constraint never throws inside
 * the try/catch at all — it throws at commit, after the loop has finished,
 * losing EVERY recipient's row. And even a synchronous failure that the catch
 * swallows has already marked the shared transaction rollback-only, so the
 * method exits with {@code UnexpectedRollbackException} and all rows are lost
 * anyway. Verified against real Postgres (see
 * {@code ListingNotificationPerRecipientTxIT}). The fix is
 * {@link NotificationService#notifyInAppInNewTx}, whose own
 * {@code REQUIRES_NEW} opens an independent transaction <em>per call</em>: a
 * failure rolls back only that one recipient's row.
 *
 * <p><b>Email caveat.</b> {@code LISTING_AVAILABLE} and
 * {@code LISTING_INTEREST_RECEIVED} have no case in
 * {@code NotificationService.mapLegacyType} (verified by reading it), so
 * {@link NotificationService#notify} would publish no {@code EmailEvent} for
 * them anyway — {@code notifyInAppInNewTx} (which never publishes one) is
 * therefore behaviorally identical here today. If a listing type is ever added
 * to {@code mapLegacyType} expecting emails, these paths will NOT send them —
 * that change must publish an explicit {@code EmailEvent} here instead.
 */
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
     * Transitions interest status to NOTIFIED only when the notification row
     * was actually written — a failed recipient stays ACTIVE so a future
     * publish retries it instead of suppressing the notification forever.
     * The interest writes stay in this listener's transaction; only the
     * notification write runs in its own (see class javadoc). Delivery is
     * therefore at-least-once: the notification row commits before the
     * interest transition does, so a crash between the two re-notifies that
     * renter on the next publish — preferred over marking first, which would
     * silently drop the notification instead.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onListingPublished(ListingPublishedEvent event) {
        log.info("ListingPublishedEvent received for listing {}", event.listingId());

        List<UnitListingInterest> activeInterests =
                interestRepository.findByListingIdAndStatus(event.listingId(), InterestStatus.ACTIVE);

        Optional<UnitListing> listingOpt = listingRepository.findById(event.listingId());
        String listingTitle = listingOpt.map(l -> l.getTitleEn() != null ? l.getTitleEn() : "A listing you wishlisted")
                .orElse("A listing you wishlisted");

        int notified = 0;
        for (UnitListingInterest interest : activeInterests) {
            // Dedupe: skip if already NOTIFIED (defensive check — query already filters, but guard here too)
            if (interest.getStatus() == InterestStatus.NOTIFIED) {
                log.debug("Interest {} already NOTIFIED — skipping", interest.getId());
                continue;
            }

            try {
                notificationService.notifyInAppInNewTx(
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
                // Leave the interest ACTIVE so a future publish retries it
                continue;
            }

            interest.setStatus(InterestStatus.NOTIFIED);
            interest.setNotifiedAt(LocalDateTime.now());
            interestRepository.save(interest);
            notified++;
        }

        log.info("ListingPublishedEvent processed: notified {}/{} renters for listing {}",
                notified, activeInterests.size(), event.listingId());
    }

    /**
     * When a renter expresses interest, notify all TENANT_ADMIN users of that tenant.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInterestReceived(InterestReceivedEvent event) {
        log.info("InterestReceivedEvent received for listing {}, renter {}",
                event.listingId(), event.renterUserId());

        Optional<UnitListing> listingOpt = listingRepository.findById(event.listingId());
        String listingTitle = listingOpt.map(l -> l.getTitleEn() != null ? l.getTitleEn() : "your listing")
                .orElse("your listing");

        Set<UserRole> notifyRoles = Set.of(UserRole.TENANT_ADMIN, UserRole.PROPERTY_MANAGER);
        List<User> tenantAdmins = userRepository.findByTenantId(event.tenantId())
                .stream()
                .filter(u -> notifyRoles.contains(u.getRole()))
                .toList();

        for (User admin : tenantAdmins) {
            try {
                notificationService.notifyInAppInNewTx(
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
