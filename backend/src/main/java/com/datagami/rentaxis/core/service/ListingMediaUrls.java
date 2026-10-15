package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.UnitListingMedia;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Where a listing photo or floor plan is fetched from (bug 26/27).
 *
 * <p>An uploaded file sits in the organisation's private blob container, so its
 * stored blob URL answers 403 to any browser or app. The API therefore never
 * hands that URL out: a stored file is named by a backend route instead —
 * the staff route (tenant- and building-scoped, any listing status) in the admin
 * DTOs, the anonymous route (live listings only) in the marketplace and public
 * DTOs. The stored row is unchanged.</p>
 *
 * <p>A row that is not a file of ours (an external URL a seed or an older client
 * stored, a video or 360-tour link) keeps its URL as it is.</p>
 */
@Component
public class ListingMediaUrls {

    /** The anonymous route; {@code SecurityConfig} already opens {@code /api/v1/public/**}. */
    public static final String PUBLIC_PREFIX = "/api/v1/public/listing-media/";

    private final BlobStorageService blobStorage;

    /**
     * The site's public origin, e.g. {@code https://app.rentaxis.com} (PR #401 R1
     * P2-1): renter-app builds already in the stores pass a photo URL straight to
     * {@code Image.network}, which cannot resolve a path, so every DTO carries an
     * absolute URL. Configured ({@code rentaxis.media.public-base-url}, defaulting
     * to {@code NEXT_PUBLIC_API_URL}, the origin Caddy serves), never taken from the
     * request's Host header. Empty on a local stack: the routes stay relative.
     */
    private final String publicBaseUrl;

    public ListingMediaUrls(BlobStorageService blobStorage) {
        this(blobStorage, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ListingMediaUrls(BlobStorageService blobStorage,
                            @org.springframework.beans.factory.annotation.Value("${rentaxis.media.public-base-url:}")
                            String publicBaseUrl) {
        this.blobStorage = blobStorage;
        this.publicBaseUrl = normaliseBase(publicBaseUrl);
    }

    /** {@code https://host[:port]} with no trailing slash, or empty when unset or not http(s). */
    static String normaliseBase(String base) {
        if (base == null || base.isBlank()) return "";
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b.startsWith("https://") || b.startsWith("http://") ? b : "";
    }

    /** For the listing's own staff: {@code <base>/api/listings/{listingId}/media/{id}/file}. */
    public String adminUrl(UnitListingMedia m) {
        if (m == null) return null;
        return storedBlobPath(m).isPresent()
                ? publicBaseUrl + "/api/listings/" + m.getListingId() + "/media/" + m.getId() + "/file"
                : m.getUrl();
    }

    /** For anyone, while the listing is live: {@code <base>/api/v1/public/listing-media/{id}}. */
    public String publicUrl(UnitListingMedia m) {
        if (m == null) return null;
        return storedBlobPath(m).isPresent() ? publicBaseUrl + PUBLIC_PREFIX + m.getId() : m.getUrl();
    }

    /**
     * The file's path inside its organisation's container, when the row names a
     * file this service stored: the recorded blob path, or — for rows written
     * before the path was recorded — the path parsed from a URL in this
     * service's own storage account, under the listing's own folder.
     */
    public Optional<String> storedBlobPath(UnitListingMedia m) {
        if (m == null) return Optional.empty();
        String recorded = m.getBlobPath();
        if (recorded != null && !recorded.isBlank()) {
            return BlobStorageService.isSafeObjectPath(recorded) ? Optional.of(recorded) : Optional.empty();
        }
        return blobStorage.parseOwnedBlobUrl(m.getUrl())
                .map(BlobStorageService.BlobLocation::blobPath)
                .filter(path -> m.getListingId() != null
                        && path.startsWith("listings/" + m.getListingId() + "/"));
    }
}
