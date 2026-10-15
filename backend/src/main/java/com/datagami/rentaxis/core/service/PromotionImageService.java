package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.util.ImageTypes;
import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.PromoBusiness;
import com.datagami.rentaxis.domain.repository.PromoAdRepository;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Promotion artwork and business logos uploaded by an admin (ux6 item 2).
 *
 * <p>The file goes to the organisation's private container,
 * {@code tenant-<id>/promotions/<uuid>.<ext>}, and the ad or business stores the
 * route it is served from, {@value #ROUTE_PREFIX}{@code <uuid>.<ext>} — never a
 * blob URL, which answers 403 to the renter's phone (bug 26/27).</p>
 *
 * <p>Anyone may load such an image only while something live shows it: an
 * active, unexpired ad of an active business uses it as artwork, or an active
 * business uses it as its logo. Before that (an upload not yet saved, a paused
 * or expired ad) only the organisation's own admins see it, through
 * {@code GET /api/v1/promotions/images/{file}}.</p>
 *
 * <p>An https link to artwork hosted elsewhere is still accepted, as before:
 * ads and businesses saved with one keep validating on their next edit.</p>
 */
@Service
public class PromotionImageService {

    public static final String ROUTE_PREFIX = "/api/v1/public/promo-images/";

    /** The stored file name: a random UUID and the sniffed type's extension. */
    private static final Pattern FILE = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|gif|webp)");

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png", "image/jpeg", "jpg", "image/gif", "gif", "image/webp", "webp");

    /** Same cap the shared asset upload had, which these fields used before. */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private final BlobStorageService blobs;
    private final PromoAdRepository ads;
    private final PromoBusinessRepository businesses;

    /**
     * The site's public origin (PR #401 R1 P2-1). A renter app already installed
     * from the store hands an image URL straight to {@code Image.network}, so the
     * DTOs carry an absolute URL; empty (a local stack) keeps it relative.
     */
    private final String publicBaseUrl;

    public PromotionImageService(BlobStorageService blobs, PromoAdRepository ads, PromoBusinessRepository businesses) {
        this(blobs, ads, businesses, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PromotionImageService(BlobStorageService blobs, PromoAdRepository ads, PromoBusinessRepository businesses,
                                 @org.springframework.beans.factory.annotation.Value("${rentaxis.media.public-base-url:}")
                                 String publicBaseUrl) {
        this.blobs = blobs;
        this.ads = ads;
        this.businesses = businesses;
        this.publicBaseUrl = ListingMediaUrls.normaliseBase(publicBaseUrl);
    }

    /** What a DTO carries: an uploaded image's route made absolute; anything else unchanged. */
    public String forDto(String stored) {
        if (stored == null || publicBaseUrl.isEmpty() || !stored.startsWith(ROUTE_PREFIX)) return stored;
        return publicBaseUrl + stored;
    }

    /**
     * What is stored: the route of an uploaded image, relative, however the client
     * sent it back (an editor re-saves the absolute URL its DTO carried).
     */
    public String normaliseForStorage(String url) {
        if (url == null) return null;
        if (!publicBaseUrl.isEmpty() && url.startsWith(publicBaseUrl + ROUTE_PREFIX)) {
            return url.substring(publicBaseUrl.length());
        }
        return url;
    }

    /**
     * An uploaded image may only be saved by the organisation whose container
     * holds it (R1 P3-2): another org's file name, copied from a public URL, is
     * refused rather than stored — so it can never make the owner's image 404.
     */
    public boolean ownedBy(UUID tenantId, String route) {
        return fileOf(route).map(file -> blobs.exists(tenantId, blobPath(file))).orElse(false);
    }

    /** Stores an uploaded image for {@code tenantId}; returns the route to save on the ad or business. */
    public String upload(UUID tenantId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleViolationException("Choose an image to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BusinessRuleViolationException("The image must be under 2 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessRuleViolationException("The image could not be read");
        }
        // The bytes decide, not the name or the browser's claim: an SVG or HTML file
        // labelled image/png is refused here and could never be served back.
        String type = ImageTypes.sniffServable(bytes, false)
                .orElseThrow(() -> new BusinessRuleViolationException("Upload a PNG, JPEG, GIF or WebP image"));
        String fileName = UUID.randomUUID() + "." + EXTENSIONS.get(type);
        blobs.uploadPromotionImage(tenantId, fileName, bytes, type);
        return ROUTE_PREFIX + fileName;
    }

    /** The stored file name when {@code url} is one of this service's routes. */
    public static Optional<String> fileOf(String url) {
        if (url == null || !url.startsWith(ROUTE_PREFIX)) return Optional.empty();
        String file = url.substring(ROUTE_PREFIX.length());
        return FILE.matcher(file).matches() ? Optional.of(file) : Optional.empty();
    }

    /** True for a well-formed file name of this service (the {@code {file}} path segment). */
    public static boolean isFileName(String file) {
        return file != null && FILE.matcher(file).matches();
    }

    /** {@code BlobStorageService} path of a stored file name. */
    public static String blobPath(String file) {
        return BlobStorageService.PROMOTIONS_FOLDER + "/" + file;
    }

    /**
     * The organisations whose live ad or business shows {@code file}, in no
     * particular order — the only case the anonymous route serves it. The route
     * reads each one's own container until it finds the file (R1 P3-2), so a name
     * collision can never serve, or hide, another organisation's image.
     */
    @Transactional(readOnly = true)
    public java.util.List<UUID> liveOwners(String file) {
        if (!isFileName(file)) return java.util.List.of();
        String route = ROUTE_PREFIX + file;
        Instant now = Instant.now();
        java.util.LinkedHashSet<UUID> owners = new java.util.LinkedHashSet<>();
        for (PromoBusiness b : businesses.findByLogoUrl(route)) {
            if (b.isActive() && b.getTenantId() != null) owners.add(b.getTenantId());
        }
        for (PromoAd ad : ads.findByBackgroundImageUrl(route)) {
            if (!ad.isActive() || (ad.getEndsAt() != null && !ad.getEndsAt().isAfter(now))) continue;
            boolean businessLive = businesses.findById(ad.getBusinessId())
                    .filter(b -> Objects.equals(b.getTenantId(), ad.getTenantId()))
                    .map(PromoBusiness::isActive)
                    .orElse(false);
            if (businessLive) owners.add(ad.getTenantId());
        }
        return java.util.List.copyOf(owners);
    }
}
