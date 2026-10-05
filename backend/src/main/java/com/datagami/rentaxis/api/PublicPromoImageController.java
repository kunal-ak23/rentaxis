package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.PromotionImageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * An uploaded promotion image, for anyone — the renter app loads ad artwork and
 * business logos with {@code CachedNetworkImageProvider}, carrying no
 * credentials. Served only while a live ad or business shows it (see
 * {@link PromotionImageService#liveOwner}); anything else is 404 without
 * reading storage.
 */
@RestController
public class PublicPromoImageController {

    private final PromotionImageService images;
    private final BlobStorageService blobs;

    public PublicPromoImageController(PromotionImageService images, BlobStorageService blobs) {
        this.images = images;
        this.blobs = blobs;
    }

    @GetMapping(PromotionImageService.ROUTE_PREFIX + "{file:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String file,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UUID owner = images.liveOwner(file).orElseThrow(() -> new NotFoundException("Image not found"));
        return ServedMedia.serve(blobs, owner, PromotionImageService.blobPath(file), false,
                ServedMedia.Caching.PUBLIC, ifNoneMatch, "promo-image|" + owner + "|" + file);
    }
}
