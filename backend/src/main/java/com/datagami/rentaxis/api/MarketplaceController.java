package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.MarketplaceSearchRequest;
import com.datagami.rentaxis.api.dto.UnitListingDTO;
import com.datagami.rentaxis.api.dto.UnitListingMediaDTO;
import com.datagami.rentaxis.api.dto.UnitListingSummaryDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.InterestService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.core.service.MarketplaceService;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingAmenityEntry;
import com.datagami.rentaxis.domain.entity.UnitListingInterest;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.enums.Furnishing;
import com.datagami.rentaxis.domain.repository.UnitListingAmenityRepository;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/marketplace")
@PreAuthorize("hasRole('RENTER')")
public class MarketplaceController {

    private final MarketplaceService marketplaceService;
    private final InterestService interestService;
    private final UnitListingMediaRepository mediaRepository;
    private final UnitListingAmenityRepository amenityRepository;
    private final UnitListingRepository listingRepository;
    private final TenantFeatureService tenantFeatureService;
    private final UnitRepository unitRepository;

    public MarketplaceController(MarketplaceService marketplaceService,
                                  InterestService interestService,
                                  UnitListingMediaRepository mediaRepository,
                                  UnitListingAmenityRepository amenityRepository,
                                  UnitListingRepository listingRepository,
                                  TenantFeatureService tenantFeatureService,
                                  UnitRepository unitRepository) {
        this.marketplaceService = marketplaceService;
        this.interestService = interestService;
        this.mediaRepository = mediaRepository;
        this.amenityRepository = amenityRepository;
        this.listingRepository = listingRepository;
        this.tenantFeatureService = tenantFeatureService;
        this.unitRepository = unitRepository;
    }

    @GetMapping("/{tenantSlug}/listings")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)   // maps lazy associations (OSIV is off)
    public ResponseEntity<Page<UnitListingSummaryDTO>> listListings(
            @PathVariable String tenantSlug,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) BigDecimal minRent,
            @RequestParam(required = false) BigDecimal maxRent,
            @RequestParam(required = false) Furnishing furnishing,
            @RequestParam(required = false) Boolean availableNow,
            @RequestParam(required = false) LocalDate availableByDate,
            @RequestParam(required = false) Double nearLat,
            @RequestParam(required = false) Double nearLng,
            @RequestParam(required = false) Double radiusKm,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);
        checkEnabled(tenantId);
        MarketplaceSearchRequest req = new MarketplaceSearchRequest(
                minBedrooms, minRent, maxRent, furnishing,
                availableNow, availableByDate, nearLat, nearLng, radiusKm);
        Page<UnitListing> page = marketplaceService.search(tenantId, req, pageable);
        return ResponseEntity.ok(page.map(this::toSummary));
    }

    @GetMapping("/{tenantSlug}/listings/{slug}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)   // maps lazy associations (OSIV is off)
    public ResponseEntity<UnitListingDTO> getBySlug(
            @PathVariable String tenantSlug,
            @PathVariable String slug) {
        UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);
        checkEnabled(tenantId);
        UnitListing listing = marketplaceService.resolveByTenantSlugAndUnitSlug(tenantSlug, slug);
        return ResponseEntity.ok(toDetail(listing, tenantSlug));
    }

    @PostMapping("/listings/{id}/interest")
    public ResponseEntity<Void> addInterest(
            @PathVariable UUID id,
            @RequestBody(required = false) NoteRequest body) {
        UnitListing listing = marketplaceService.getListingById(id);
        checkEnabled(listing.getTenantId());
        UUID renterUserId = currentUserId();
        interestService.addInterest(listing.getTenantId(), id, renterUserId,
                body != null ? body.note() : null);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/listings/{id}/interest")
    public ResponseEntity<Void> withdrawInterest(@PathVariable UUID id) {
        UnitListing listing = marketplaceService.getListingById(id);
        checkEnabled(listing.getTenantId());
        UUID renterUserId = currentUserId();
        interestService.withdraw(listing.getTenantId(), id, renterUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/wishlist")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)   // maps lazy associations (OSIV is off)
    public ResponseEntity<List<UnitListingSummaryDTO>> wishlist() {
        // No checkEnabled() here: wishlist is intentionally cross-tenant (a renter may
        // have wishlisted listings from multiple landlords). A per-tenant feature gate
        // cannot meaningfully apply. Hiding the wishlist after disabling LISTINGS would
        // confuse renters who already expressed interest.
        UUID renterUserId = currentUserId();
        List<UnitListingInterest> interests = interestService.wishlistForRenter(renterUserId);
        if (interests.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Wishlist is intentionally cross-tenant: a renter may have wishlisted
        // listings across multiple landlords. We bypass MarketplaceService
        // (which would apply tenant scoping) and load the listings directly
        // by id in a single query. NOTE: derived JpaRepository queries are
        // not intercepted by TenantAspect, so this is safe today; if that
        // changes, this query must use @Query(nativeQuery = true) or be
        // explicitly excluded.
        List<UUID> listingIds = interests.stream()
                .map(UnitListingInterest::getListingId)
                .collect(Collectors.toList());
        List<UnitListing> listings = listingRepository.findAllByIdIn(listingIds);
        Map<UUID, UnitListing> byId = new HashMap<>();
        for (UnitListing l : listings) {
            byId.put(l.getId(), l);
        }
        List<UnitListingSummaryDTO> result = new ArrayList<>();
        for (UnitListingInterest interest : interests) {
            UnitListing listing = byId.get(interest.getListingId());
            if (listing != null) {
                result.add(toSummary(listing));
            }
        }
        return ResponseEntity.ok(result);
    }

    public record NoteRequest(String note) {
    }

    // ---- Helpers ----

    private void checkEnabled(UUID tenantId) {
        if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LISTINGS)) {
            throw new NotFoundException("Listings feature is disabled");
        }
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private UnitListingSummaryDTO toSummary(UnitListing l) {
        List<UnitListingMedia> media = mediaRepository.findByListingIdOrderBySortOrderAsc(l.getId());
        String coverUrl = media.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsCover()))
                .findFirst()
                .or(() -> media.stream().findFirst())
                .map(UnitListingMedia::getUrl)
                .orElse(null);

        String propertyName = unitRepository.findById(l.getUnitId())
                .map(u -> u.getProperty() != null ? u.getProperty().getNameEn() : null)
                .orElse(null);

        return new UnitListingSummaryDTO(
                l.getId(),
                l.getTitleEn(),
                propertyName,
                l.getBedrooms(),
                l.getBathrooms(),
                l.getAnnualRent(),
                l.getStatus(),
                coverUrl,
                0L,
                l.getLat(),
                l.getLng(),
                l.getCreatedAt(),
                l.getUpdatedAt(),
                l.getSlug()
        );
    }

    private UnitListingDTO toDetail(UnitListing l, String tenantSlug) {
        List<UnitListingMedia> media = mediaRepository.findByListingIdOrderBySortOrderAsc(l.getId());
        List<UnitListingAmenityEntry> amenityEntries = amenityRepository.findByListingId(l.getId());

        List<UnitListingDTO.AmenityEntry> amenities = amenityEntries.stream()
                .map(a -> new UnitListingDTO.AmenityEntry(a.getAmenity(), a.getCustomLabel()))
                .toList();

        List<UnitListingMediaDTO> mediaDtos = media.stream()
                .map(m -> new UnitListingMediaDTO(m.getId(), m.getMediaType(), m.getUrl(),
                        m.getCaption(), m.getSortOrder(), m.getIsCover()))
                .toList();

        return new UnitListingDTO(
                l.getId(), l.getUnitId(), l.getStatus(),
                l.getTitleEn(), l.getTitleAr(), l.getDescriptionEn(), l.getDescriptionAr(),
                l.getBedrooms(), l.getBathrooms(), l.getSizeSqft(), l.getFloor(), l.getParkingSpaces(),
                l.getFurnishing(), l.getViewType(),
                l.getAnnualRent(), l.getSecurityDeposit(), l.getMinLeaseMonths(),
                l.getChequesAccepted(), l.getDewaIncluded(), l.getChillerIncluded(),
                l.getUtilitiesEstimate(), l.getAvailableFrom(),
                tenantSlug, l.getSlug(), l.getSeoTitle(), l.getSeoDescription(), l.getSeoKeywords(), l.getOgImageUrl(),
                l.getLat(), l.getLng(),
                l.getPublishedAt(), l.getCreatedAt(), l.getUpdatedAt(),
                amenities, mediaDtos
        );
    }
}
