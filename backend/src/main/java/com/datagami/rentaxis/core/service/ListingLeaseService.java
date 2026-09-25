package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.InterestStatus;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitListingInterestRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F14-51: a listing enquiry becomes a draft lease. The renter is the tenant's renter
 * with the enquirer's email, or a new renter record — record only, no login (the
 * enquirer's marketplace account is not given access to this organisation). The
 * draft carries the listing's unit, rent, deposit and cheque count, starting when
 * the unit is available; the user reviews and posts it as any draft. Posting it
 * unpublishes the listing ({@code UnitListingService.onLeasePosted}).
 */
@Service
public class ListingLeaseService {

    private final UnitListingService listings;
    private final UnitListingInterestRepository interests;
    private final UserRepository users;
    private final RenterRepository renters;
    private final RenterService renterService;
    private final LeaseService leaseService;

    public ListingLeaseService(UnitListingService listings, UnitListingInterestRepository interests,
                               UserRepository users, RenterRepository renters, RenterService renterService,
                               LeaseService leaseService) {
        this.listings = listings;
        this.interests = interests;
        this.users = users;
        this.renters = renters;
        this.renterService = renterService;
        this.leaseService = leaseService;
    }

    @Transactional
    public LeaseDTO createLease(UUID tenantId, UUID listingId, UUID interestId) {
        UnitListing listing = listings.get(tenantId, listingId);
        UnitListingInterest interest = interests.findById(interestId)
                .filter(i -> listing.getId().equals(i.getListingId()))
                .orElseThrow(() -> new NotFoundException("Interest not found"));
        if (interest.getStatus() == InterestStatus.CONVERTED && interest.getLeaseId() != null) {
            throw new BusinessRuleViolationException("This enquiry already has a draft lease.",
                    "listing.interestConverted", Map.of());
        }
        User enquirer = users.findById(interest.getRenterUserId())
                .orElseThrow(() -> new NotFoundException("The enquirer's account is gone"));

        UUID renterId = existingRenter(tenantId, enquirer);
        if (renterId == null) {
            CreateRenterDTO r = new CreateRenterDTO();
            r.setNameEn(enquirer.getName() != null && !enquirer.getName().isBlank() ? enquirer.getName() : enquirer.getEmail());
            r.setEmail(enquirer.getEmail());
            r.setPhone(enquirer.getPhoneNumber());
            r.setCreatePortalAccount(false);
            renterId = renterService.createRenter(r).getId();
        }

        LocalDate today = LocalDate.now();
        LocalDate start = listing.getAvailableFrom() != null && listing.getAvailableFrom().isAfter(today)
                ? listing.getAvailableFrom() : today;
        int months = listing.getMinLeaseMonths() != null && listing.getMinLeaseMonths() > 0 ? listing.getMinLeaseMonths() : 12;
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(listing.getUnitId());
        dto.setRenterId(renterId);
        dto.setContractDate(today);
        dto.setStartDate(start);
        dto.setEndDate(start.plusMonths(months).minusDays(1));
        if (listing.getChequesAccepted() != null && listing.getChequesAccepted() > 0) {
            dto.setPaymentTerms(listing.getChequesAccepted());
        }
        List<LeaseLineInput> lines = new ArrayList<>();
        if (listing.getAnnualRent() != null && listing.getAnnualRent().signum() > 0) {
            lines.add(new LeaseLineInput(null, "RENT", scaledRent(listing.getAnnualRent(), months), BigDecimal.ZERO,
                    null, null, null, null, null));
        }
        if (listing.getSecurityDeposit() != null && listing.getSecurityDeposit().signum() > 0) {
            lines.add(new LeaseLineInput(null, "SECURITY_DEPOSIT", listing.getSecurityDeposit(), BigDecimal.ZERO,
                    null, null, null, null, null));
        }
        dto.setLines(lines);
        LeaseDTO lease = leaseService.createDraftLease(dto);

        interest.setStatus(InterestStatus.CONVERTED);
        interest.setLeaseId(lease.getId());
        interests.save(interest);
        return lease;
    }

    /** The listing quotes a year's rent; a term of other length pro-rates it by months. */
    static BigDecimal scaledRent(BigDecimal annual, int months) {
        if (months == 12) return annual;
        return annual.multiply(BigDecimal.valueOf(months)).divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP);
    }

    private UUID existingRenter(UUID tenantId, User enquirer) {
        if (enquirer.getEmail() == null || enquirer.getEmail().isBlank()) return null;
        return renters.findByTenantIdAndEmailIn(tenantId, List.of(enquirer.getEmail())).stream()
                .filter(r -> Objects.equals(tenantId, r.getTenantId()))
                .map(Renter::getId).findFirst().orElse(null);
    }
}
