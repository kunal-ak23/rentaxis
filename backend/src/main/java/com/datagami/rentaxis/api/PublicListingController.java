package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.MarketplaceSearchRequest;
import com.datagami.rentaxis.api.dto.PublicListingDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.config.FeatureFlags;
import com.datagami.rentaxis.core.service.MarketplaceService;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.enums.Furnishing;
import com.datagami.rentaxis.domain.entity.enums.ListingStatus;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/public/l")
public class PublicListingController {

    // Per-listing pseudo-random offset, ~220m max in any direction.
    // Deterministic per listing id (so repeat loads give the same coords)
    // but different per listing (so reversing requires knowing the seed).
    private static final double MAX_OFFSET_DEG = 0.002;

    private static final long RENT_ROUND = 10_000L;

    private final MarketplaceService marketplaceService;
    private final UnitListingMediaRepository mediaRepository;
    private final UnitListingRepository listingRepository;
    private final FeatureFlags featureFlags;

    public PublicListingController(MarketplaceService marketplaceService,
                                    UnitListingMediaRepository mediaRepository,
                                    UnitListingRepository listingRepository,
                                    FeatureFlags featureFlags) {
        this.marketplaceService = marketplaceService;
        this.mediaRepository = mediaRepository;
        this.listingRepository = listingRepository;
        this.featureFlags = featureFlags;
    }

    @GetMapping("/{tenantSlug}")
    public ResponseEntity<Page<PublicListingDTO>> listPublicListings(
            @PathVariable String tenantSlug,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) java.math.BigDecimal minRent,
            @RequestParam(required = false) java.math.BigDecimal maxRent,
            @RequestParam(required = false) Furnishing furnishing,
            @RequestParam(required = false) Boolean availableNow,
            @PageableDefault(size = 12, sort = "createdAt") Pageable pageable) {
        checkEnabled();
        UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);
        MarketplaceSearchRequest req = new MarketplaceSearchRequest(
                minBedrooms, minRent, maxRent, furnishing, availableNow, null, null, null, null);
        Page<UnitListing> page = marketplaceService.search(tenantId, req, pageable);
        return ResponseEntity.ok(page.map(l -> toPublicDTO(l, tenantSlug)));
    }

    @GetMapping("/{tenantSlug}/{unitSlug}")
    public ResponseEntity<PublicListingDTO> getPublicListing(
            @PathVariable String tenantSlug,
            @PathVariable String unitSlug) {
        checkEnabled();
        UnitListing listing = marketplaceService.resolveByTenantSlugAndUnitSlug(tenantSlug, unitSlug);
        return ResponseEntity.ok(toPublicDTO(listing, tenantSlug));
    }

    @GetMapping(value = "/{tenantSlug}/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap(@PathVariable String tenantSlug) {
        checkEnabled();
        UUID tenantId = marketplaceService.resolveTenantSlug(tenantSlug);

        Page<UnitListing> page = listingRepository.findByTenantIdAndStatus(
                tenantId, ListingStatus.PUBLISHED, PageRequest.of(0, 1000));

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (UnitListing listing : page.getContent()) {
            String lastmod = listing.getUpdatedAt() != null
                    ? listing.getUpdatedAt().toLocalDate().toString()
                    : java.time.LocalDate.now().toString();
            sb.append(String.format(
                    "  <url>\n    <loc>/l/%s/%s</loc>\n    <lastmod>%s</lastmod>\n    <changefreq>weekly</changefreq>\n  </url>\n",
                    tenantSlug, listing.getSlug(), lastmod));
        }

        sb.append("</urlset>");
        return ResponseEntity.ok(sb.toString());
    }

    // ---- Mapping ----

    private PublicListingDTO toPublicDTO(UnitListing l, String tenantSlug) {
        // Cover photo: isCover = true, else first by sortOrder
        List<UnitListingMedia> media = mediaRepository.findByListingIdOrderBySortOrderAsc(l.getId());
        String coverUrl = media.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsCover()))
                .findFirst()
                .or(() -> media.stream().findFirst())
                .map(UnitListingMedia::getUrl)
                .orElse(null);

        // Rent: round down to nearest 10k, format as "AED 80-90k"
        String rentLabel = buildRentLabel(l.getAnnualRent());

        // Approximate coordinates with per-listing pseudo-random offset
        double[] offset = computeOffset(l.getId());
        BigDecimal approxLat = l.getLat() != null
                ? l.getLat().add(BigDecimal.valueOf(offset[0]))
                : null;
        BigDecimal approxLng = l.getLng() != null
                ? l.getLng().add(BigDecimal.valueOf(offset[1]))
                : null;

        // Available label
        String availableLabel = buildAvailableLabel(l);

        // SEO: use listing SEO fields, fall back to title
        String seoTitle = l.getSeoTitle() != null ? l.getSeoTitle() : l.getTitleEn();
        String seoDesc = l.getSeoDescription();
        String seoKeywords = l.getSeoKeywords();
        String ogImage = l.getOgImageUrl() != null ? l.getOgImageUrl() : coverUrl;

        String furnishing = l.getFurnishing() != null ? l.getFurnishing().name() : null;

        return new PublicListingDTO(
                l.getSlug(),
                tenantSlug,
                null,   // buildingName — not available without property join (v1)
                null,   // area
                null,   // emirate
                l.getBedrooms(),
                l.getBathrooms(),
                furnishing,
                rentLabel,
                coverUrl,
                approxLat,
                approxLng,
                seoTitle,
                seoDesc,
                seoKeywords,
                ogImage,
                availableLabel,
                true
        );
    }

    private String buildRentLabel(BigDecimal rent) {
        if (rent == null) return null;
        long rentLong = rent.longValue();
        long low = (rentLong / RENT_ROUND) * RENT_ROUND;
        long high = low + RENT_ROUND;
        return String.format("AED %dk-%dk", low / 1000, high / 1000);
    }

    private String buildAvailableLabel(UnitListing l) {
        if (l.getAvailableFrom() == null) {
            return "Available now";
        }
        String month = l.getAvailableFrom().format(DateTimeFormatter.ofPattern("MMMM"));
        int year = l.getAvailableFrom().getYear();
        return "Available from " + month + " " + year;
    }

    private double[] computeOffset(UUID listingId) {
        if (listingId == null) {
            return new double[]{0.0, 0.0};
        }
        long seed = listingId.getMostSignificantBits() ^ listingId.getLeastSignificantBits();
        java.util.Random r = new java.util.Random(seed);
        double lat = (r.nextDouble() - 0.5) * 2 * MAX_OFFSET_DEG;
        double lng = (r.nextDouble() - 0.5) * 2 * MAX_OFFSET_DEG;
        return new double[]{lat, lng};
    }

    private void checkEnabled() {
        if (!featureFlags.isListingsEnabled()) {
            throw new NotFoundException("Listings feature is disabled");
        }
    }
}
