package com.datagami.rentaxis.core.util;

import java.util.Optional;

/**
 * Identifies a raster image by its leading magic bytes, never by a declared
 * content type or file extension, which are the uploader's claims.
 */
public final class ImageTypes {

    private ImageTypes() {
    }

    /** {@code image/png}, {@code image/jpeg} or {@code image/gif}; anything else is empty. */
    public static Optional<String> sniff(byte[] b) {
        if (b == null) {
            return Optional.empty();
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return Optional.of("image/png");
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (b.length >= 4 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38) {
            return Optional.of("image/gif");
        }
        return Optional.empty();
    }

    /**
     * The type a stored file may be served to a browser as, from its magic
     * number: PNG, JPEG, GIF, WebP, and PDF only where {@code allowPdf}. Empty for
     * anything else (SVG, HTML, a renamed executable), which is then not served.
     */
    public static Optional<String> sniffServable(byte[] b, boolean allowPdf) {
        if (b == null || b.length < 4) return Optional.empty();
        Optional<String> raster = sniff(b);
        if (raster.isPresent()) return raster;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF, b2 = b[2] & 0xFF, b3 = b[3] & 0xFF;
        if (b.length >= 12 && b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return Optional.of("image/webp");
        }
        if (allowPdf && b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') return Optional.of("application/pdf");
        return Optional.empty();
    }
}
