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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class MarketplaceService {

    private static final double DEGREES_PER_KM = 1.0 / 111.0;

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
        return listingRepository.findAll(spec, pageable);
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

                    predicates.add(cb.between(root.get("lat"), latMin, latMax));
                    predicates.add(cb.between(root.get("lng"), lngMin, lngMax));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
