package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.ListingMediaUrls;
import com.datagami.rentaxis.core.service.MarketplaceService;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.domain.entity.UnitListing;
import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.UnitListingMediaRepository;
import com.datagami.rentaxis.domain.repository.UnitListingRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A live listing's photo or floor plan, for anyone (bug 26/27): the public
 * listing pages and the marketplace render these with a plain {@code <img>} or
 * {@code Image.network}, carrying no credentials.
 *
 * <p>Only what the marketplace itself shows: a PUBLISHED or UPCOMING listing,
 * not a unit still let on its available-from date, of an organisation with
 * listings switched on. Anything else — a draft, an unlisted or deleted
 * listing, another listing's id, an external link — is the same 404, so the
 * route says nothing about what exists. Staff see drafts through
 * {@code GET /api/listings/{id}/media/{mediaId}/file}.</p>
 */
@RestController
public class PublicListingMediaController {

    private final UnitListingMediaRepository mediaRepository;
    private final UnitListingRepository listingRepository;
    private final MarketplaceService marketplaceService;
    private final TenantFeatureService tenantFeatureService;
    private final ListingMediaUrls mediaUrls;
    private final BlobStorageService blobStorage;

    public PublicListingMediaController(UnitListingMediaRepository mediaRepository,
                                        UnitListingRepository listingRepository,
                                        MarketplaceService marketplaceService,
                                        TenantFeatureService tenantFeatureService,
                                        ListingMediaUrls mediaUrls,
                                        BlobStorageService blobStorage) {
        this.mediaRepository = mediaRepository;
        this.listingRepository = listingRepository;
        this.marketplaceService = marketplaceService;
        this.tenantFeatureService = tenantFeatureService;
        this.mediaUrls = mediaUrls;
        this.blobStorage = blobStorage;
    }

    @GetMapping(ListingMediaUrls.PUBLIC_PREFIX + "{mediaId}")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> media(@PathVariable UUID mediaId,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UnitListingMedia media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Media not found"));
        UnitListing listing = listingRepository.findById(media.getListingId())
                .orElseThrow(() -> new NotFoundException("Media not found"));
        if (listing.getTenantId() == null
                || !tenantFeatureService.isEnabled(listing.getTenantId(), TenantFeature.LISTINGS)
                || !marketplaceService.isPubliclyVisible(listing)) {
            throw new NotFoundException("Media not found");
        }
        String blobPath = mediaUrls.storedBlobPath(media)
                .orElseThrow(() -> new NotFoundException("Media not found"));
        return ServedMedia.serve(blobStorage, listing.getTenantId(), blobPath, true,
                ServedMedia.Caching.PUBLIC, ifNoneMatch, "listing-media|" + mediaId + "|" + blobPath);
    }
}
