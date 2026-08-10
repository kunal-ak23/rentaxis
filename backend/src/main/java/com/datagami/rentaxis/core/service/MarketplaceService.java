package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.MarketplaceSearchRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MarketplaceService {

    private static final double DEGREES_PER_KM = 1.0 / 111.0;

    // UnitListing properties a client may sort the marketplace search by. Sort is
    // client-controlled (Pageable `sort` param); an unknown property (e.g. a stale
    // "distance,asc" from an old web URL) would otherwise throw
    // PropertyReferenceException inside findAll and surface as a 500.
    private static final Set<String> SORTABLE_PROPERTIES =
            Set.of("createdAt", "updatedAt", "publishedAt", "annualRent", "bedrooms");

    private final UnitListingRepository listingRepository;
    private final LandlordOrgRepository landlordOrgRepository;

    public MarketplaceService(UnitListingRepository listingRepository,
                              LandlordOrgRepository landlordOrgRepository) {
        this.listingRepository = listingRepository;
        this.landlordOrgRepository = landlordOrgRepository;
    }

    public UUID resolveTenantSlug(String tenantSlug) {
        LandlordOrg org = landlordOrgRepository.findBySlug(tenantSlug)
                .orElseThrow(() -> new NotFoundException("Tenant not found: " + tenantSlug));
        return org.getId();
    }

    public Page<UnitListing> search(UUID tenantId, MarketplaceSearchRequest req, Pageable pageable) {
        Specification<UnitListing> spec = buildSpec(tenantId, req);
        return listingRepository.findAll(spec, sanitizeSort(pageable));
    }

    /**
     * Drops sort orders referencing non-whitelisted properties so a bad client
     * sort param degrades to the default ordering instead of a 500.
     */
    private Pageable sanitizeSort(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return pageable;
        }
        List<Sort.Order> allowed = pageable.getSort().stream()
                .filter(o -> SORTABLE_PROPERTIES.contains(o.getProperty()))
                .toList();
        if (allowed.size() == pageable.getSort().toList().size()) {
            return pageable;
        }
        Sort sort = allowed.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "createdAt")
                : Sort.by(allowed);
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    public UnitListing getBySlug(UUID tenantId, String slug) {
        UnitListing listing = listingRepository.findBySlugAndTenantId(slug, tenantId)
                .orElseThrow(() -> new NotFoundException("Listing not found: " + slug));
        if (listing.getStatus() != ListingStatus.PUBLISHED && listing.getStatus() != ListingStatus.UPCOMING) {
            throw new NotFoundException("Listing not found: " + slug);
        }
        return listing;
    }

    public UnitListing resolveByTenantSlugAndUnitSlug(String tenantSlug, String unitSlug) {
        UUID tenantId = resolveTenantSlug(tenantSlug);
        return getBySlug(tenantId, unitSlug);
    }

    /**
     * Get a listing by its primary key (any status), used internally for tenant ID resolution.
     */
    public UnitListing getListingById(UUID listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new NotFoundException("Listing not found: " + listingId));
    }

    // ---- Spec builder ----

    private Specification<UnitListing> buildSpec(UUID tenantId, MarketplaceSearchRequest req) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // Always tenant-scoped
            predicates.add(cb.equal(root.get("tenantId"), tenantId));

            // Always PUBLISHED
            predicates.add(cb.equal(root.get("status"), ListingStatus.PUBLISHED));

            if (req != null) {
                if (req.minBedrooms() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("bedrooms"), req.minBedrooms()));
                }

                if (req.minRent() != null && req.maxRent() != null) {
                    predicates.add(cb.between(root.get("annualRent"), req.minRent(), req.maxRent()));
                } else if (req.minRent() != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("annualRent"), req.minRent()));
                } else if (req.maxRent() != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("annualRent"), req.maxRent()));
                }

                if (req.furnishing() != null) {
                    predicates.add(cb.equal(root.get("furnishing"), req.furnishing()));
                }

                if (Boolean.TRUE.equals(req.availableNow())) {
                    java.time.LocalDate byDate = req.availableByDate() != null
                            ? req.availableByDate()
                            : java.time.LocalDate.now();
                    predicates.add(cb.or(
                            cb.isNull(root.get("availableFrom")),
                            cb.lessThanOrEqualTo(root.get("availableFrom"), byDate)
                    ));
                }

                if (req.nearLat() != null && req.nearLng() != null && req.radiusKm() != null) {
                    double deltaLat = req.radiusKm() * DEGREES_PER_KM;
                    double deltaLng = req.radiusKm() * DEGREES_PER_KM;

                    BigDecimal latMin = BigDecimal.valueOf(req.nearLat() - deltaLat);
                    BigDecimal latMax = BigDecimal.valueOf(req.nearLat() + deltaLat);
                    BigDecimal lngMin = BigDecimal.valueOf(req.nearLng() - deltaLng);
                    BigDecimal lngMax = BigDecimal.valueOf(req.nearLng() + deltaLng);

                    // Coordinates are optional on listings. A plain BETWEEN is never
                    // true for NULL lat/lng, which would silently hide every listing
                    // whose landlord skipped the map pin from any renter browsing
                    // with the (auto-seeded) near-me filter. Keep coordinate-less
                    // listings visible; only listings with coordinates OUTSIDE the
                    // radius are filtered out.
                    Predicate withinBox = cb.and(
                            cb.between(root.get("lat"), latMin, latMax),
                            cb.between(root.get("lng"), lngMin, lngMax));
                    Predicate noCoordinates = cb.or(
                            cb.isNull(root.get("lat")),
                            cb.isNull(root.get("lng")));
                    predicates.add(cb.or(withinBox, noCoordinates));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
