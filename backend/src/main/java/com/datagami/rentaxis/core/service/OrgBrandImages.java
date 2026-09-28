package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.util.ImageTypes;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * An organisation's logo or stamp as an inline {@code data:} image for a PDF, or
 * nothing.
 *
 * <p>The URLs are free text on the organisation. The PDF renderer loads nothing
 * but {@code data:} URIs ({@link com.datagami.rentaxis.core.util.PdfResourcePolicy}),
 * so the bytes are read here through the storage SDK — and only from this storage
 * account's {@code shared} public-assets folder or this tenant's own container
 * ({@link BlobStorageService#downloadOwnedUrl}). A URL anywhere else (another host,
 * cloud metadata, {@code file:}) is never fetched: it is refused and the image is
 * left off. Whatever is read must prove itself a PNG, JPEG or GIF by its magic
 * bytes; the stored content type and the extension are the uploader's claims.</p>
 *
 * <p>Shared by the receipt, the tax invoice and the tenancy contract so the three
 * documents accept exactly the same images.</p>
 */
public final class OrgBrandImages {

    /**
     * The largest image inlined: the upload form's own cap (2 MB), so anything the
     * form accepts prints, and nothing bigger is base64-inflated into every PDF.
     */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    private OrgBrandImages() {
    }

    /** A verified raster image: its bytes and the type they prove. */
    public record Image(byte[] bytes, String type) {
        public String dataUri() {
            return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
        }
    }

    /** A {@code data:image/...;base64,...} URI for the image at {@code url}, or empty. */
    public static Optional<String> dataUri(BlobStorageService blobs, UUID tenantId, String url) {
        return load(blobs, tenantId, url).map(Image::dataUri);
    }

    /**
     * The image at {@code url}, read from our own storage only: Azure (the shared
     * public-assets folder or {@code tenantId}'s own container) or, without Azure,
     * the local assets folder. Also used to stream the logo to the browser, so the
     * header never loads a storage URL itself.
     */
    public static Optional<Image> load(BlobStorageService blobs, UUID tenantId, String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String u = url.strip();
        if (u.regionMatches(true, 0, "data:", 0, 5)) {
            return inlineDataUri(u);
        }
        if (blobs == null) {
            return Optional.empty();
        }
        Optional<BlobStorageService.DownloadResult> read = u.startsWith("/api/v1/assets/serve/")
                ? blobs.readLocalPublicAsset(u, MAX_BYTES)
                : blobs.downloadOwnedUrl(tenantId, u, MAX_BYTES);
        return read.map(BlobStorageService.DownloadResult::bytes).flatMap(OrgBrandImages::verify);
    }

    /** Checks bytes are a PNG, JPEG or GIF within the cap; the upload endpoint uses it too. */
    public static Optional<Image> verify(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            return Optional.empty();
        }
        return ImageTypes.sniff(bytes).map(type -> new Image(bytes, type));
    }

    /**
     * A {@code data:} URI already on the organisation is re-encoded from its decoded
     * bytes, so only a real raster image survives and nothing of the original string
     * (quotes, markup) reaches the HTML.
     */
    private static Optional<Image> inlineDataUri(String u) {
        int comma = u.indexOf(',');
        if (comma < 0 || !u.substring(0, comma).toLowerCase(java.util.Locale.ROOT).endsWith(";base64")) {
            return Optional.empty();
        }
        try {
            return verify(Base64.getDecoder().decode(u.substring(comma + 1).strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

}
