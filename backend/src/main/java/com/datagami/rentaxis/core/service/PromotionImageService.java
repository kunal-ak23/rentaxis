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

    public PromotionImageService(BlobStorageService blobs, PromoAdRepository ads, PromoBusinessRepository businesses) {
        this.blobs = blobs;
        this.ads = ads;
        this.businesses = businesses;
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
     * The organisation whose live ad or business shows {@code file}, if any —
     * the only case the anonymous route serves it.
     */
    @Transactional(readOnly = true)
    public Optional<UUID> liveOwner(String file) {
        if (!isFileName(file)) return Optional.empty();
        String route = ROUTE_PREFIX + file;
        Instant now = Instant.now();
        for (PromoBusiness b : businesses.findByLogoUrl(route)) {
            if (b.isActive()) return Optional.of(b.getTenantId());
        }
        for (PromoAd ad : ads.findByBackgroundImageUrl(route)) {
            if (!ad.isActive() || (ad.getEndsAt() != null && !ad.getEndsAt().isAfter(now))) continue;
            boolean businessLive = businesses.findById(ad.getBusinessId())
                    .filter(b -> Objects.equals(b.getTenantId(), ad.getTenantId()))
                    .map(PromoBusiness::isActive)
                    .orElse(false);
            if (businessLive) return Optional.of(ad.getTenantId());
        }
        return Optional.empty();
    }
}
