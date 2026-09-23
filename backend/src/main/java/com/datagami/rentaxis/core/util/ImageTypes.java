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
}
