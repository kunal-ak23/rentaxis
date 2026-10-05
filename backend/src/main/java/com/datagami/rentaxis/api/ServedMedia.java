package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BlobStorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Streams an image (or a floor-plan PDF) out of an organisation's private blob
 * container to a browser or app — the shape every media route in this package
 * answers with, so listing photos and promotion artwork behave the same as the
 * cheque scan route of #398.
 *
 * <p>The type is decided by the file's own leading bytes, never by the name or
 * the uploader's claim, and only from an allow-list: PNG, JPEG, GIF, WebP and
 * (where the caller allows it) PDF. Anything else is refused as not found, so an
 * SVG or HTML file can never be served from the app's origin. Every answer
 * carries {@code nosniff}; an image also a sandboxing CSP.</p>
 */
public final class ServedMedia {

    public static final Set<String> IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private ServedMedia() {
    }

    /** How long a browser (and, for public media, a shared cache) may keep the bytes. */
    public enum Caching {
        /** A live listing's photo, a promotion's artwork: anyone may cache it for an hour. */
        PUBLIC("public, max-age=3600"),
        /** Staff-only bytes: the browser only, briefly. */
        PRIVATE("private, max-age=300");

        final String header;

        Caching(String header) {
            this.header = header;
        }
    }

    /** The served type of {@code bytes}, from their magic number; empty when not on the allow-list. */
    public static Optional<String> sniff(byte[] b, boolean allowPdf) {
        return com.datagami.rentaxis.core.util.ImageTypes.sniffServable(b, allowPdf);
    }

    /**
     * Reads {@code blobPath} from {@code tenantId}'s container and answers it. A
     * blob the container no longer holds, or bytes that are not an allowed type,
     * is a 404 — like every other "not there" on these routes.
     */
    public static ResponseEntity<byte[]> serve(BlobStorageService blobs, UUID tenantId, String blobPath,
                                               boolean allowPdf, Caching caching, String ifNoneMatch,
                                               String etagSeed) {
        String etag = "\"" + UUID.nameUUIDFromBytes(etagSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(etag);
        headers.setCacheControl(caching.header);
        headers.set("X-Content-Type-Options", "nosniff");
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            // Each upload is a new name, so the same reference always means the same bytes.
            return new ResponseEntity<>(headers, HttpStatus.NOT_MODIFIED);
        }
        BlobStorageService.DownloadResult download;
        try {
            download = blobs.download(tenantId, blobPath);
        } catch (BlobStorageService.BlobStorageException e) {
            if (e.getCause() instanceof com.azure.storage.blob.models.BlobStorageException azure
                    && azure.getStatusCode() == 404) {
                throw new NotFoundException("Media not found");
            }
            throw e;
        }
        String type = sniff(download.bytes(), allowPdf)
                .orElseThrow(() -> new NotFoundException("Media not found"));
        headers.set(HttpHeaders.CONTENT_TYPE, type);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline");
        if (IMAGE_TYPES.contains(type)) {
            // Opened on its own, a raster image has nothing to run; the sandbox is
            // belt and braces. Not for a PDF: Chrome will not show one in a sandbox.
            headers.set("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
        }
        return new ResponseEntity<>(download.bytes(), headers, HttpStatus.OK);
    }
}
