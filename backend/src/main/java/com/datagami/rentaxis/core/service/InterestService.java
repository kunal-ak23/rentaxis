package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.event.InterestReceivedEvent;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class InterestService {

    private final UnitListingRepository listingRepository;
    private final UnitListingInterestRepository interestRepository;
    private final ApplicationEventPublisher eventPublisher;

    public InterestService(UnitListingRepository listingRepository,
                           UnitListingInterestRepository interestRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.listingRepository = listingRepository;
        this.interestRepository = interestRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Add interest for a renter in a listing. Idempotent: if already exists, returns the existing row.
     */
    public UnitListingInterest addInterest(UUID tenantId, UUID listingId, UUID renterUserId, String note) {
        verifyListingOwnership(tenantId, listingId);

        Optional<UnitListingInterest> existing = interestRepository.findByListingIdAndRenterUserId(listingId, renterUserId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UnitListingInterest interest = new UnitListingInterest();
        interest.setTenantId(tenantId);
        interest.setListingId(listingId);
        interest.setRenterUserId(renterUserId);
        interest.setNote(note);
        interest.setStatus(InterestStatus.ACTIVE);
        UnitListingInterest saved = interestRepository.save(interest);

        eventPublisher.publishEvent(new InterestReceivedEvent(
                saved.getId(), listingId, renterUserId, tenantId));

        return saved;
    }

    /**
     * Withdraw interest. Idempotent if already withdrawn or missing.
     */
    public void withdraw(UUID tenantId, UUID listingId, UUID renterUserId) {
        Optional<UnitListingInterest> existing = interestRepository.findByListingIdAndRenterUserId(listingId, renterUserId);
        if (existing.isEmpty()) {
            return;
        }
        UnitListingInterest interest = existing.get();
        if (!Objects.equals(interest.getTenantId(), tenantId)) {
            return; // Cross-tenant: silently ignore
        }
        if (interest.getStatus() == InterestStatus.WITHDRAWN) {
            return; // Already withdrawn
        }
        interest.setStatus(InterestStatus.WITHDRAWN);
        interestRepository.save(interest);
    }

    /**
     * List ACTIVE interests for a listing. Verifies listing ownership by tenant.
     */
    @Transactional(readOnly = true)
    public List<UnitListingInterest> listForListing(UUID tenantId, UUID listingId) {
        verifyListingOwnership(tenantId, listingId);
        return interestRepository.findByListingIdAndStatus(listingId, InterestStatus.ACTIVE);
    }

    /**
     * All ACTIVE interests for a renter (cross-tenant wishlist — no tenant filter).
     */
    @Transactional(readOnly = true)
    public List<UnitListingInterest> wishlistForRenter(UUID renterUserId) {
        return interestRepository.findByRenterUserIdAndStatus(renterUserId, InterestStatus.ACTIVE);
    }

    // ---- Helpers ----

    private void verifyListingOwnership(UUID tenantId, UUID listingId) {
        UnitListing listing = listingRepository.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Listing not found"));
        if (!Objects.equals(listing.getTenantId(), tenantId)) {
            throw new NotFoundException("Listing not found");
        }
    }
}
