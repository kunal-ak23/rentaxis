package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * One cheque found in an upload, with the server-issued image that shows it.
 *
 * @param imageId      the issued image's id ({@code cheque_image_uploads.id})
 * @param image        the issued image; {@code image.blobPath} is what bulk-attach takes
 * @param page         1-based page the cheque is on
 * @param box          where the extractor saw it on that page; null when it gave none
 * @param thumbnailUrl a small JPEG data URL of {@code image}; null when undecodable
 * @param extracted    the fields read, or null when extraction failed
 * @param warnings     per-cheque warnings (English; for API clients and logs)
 * @param flags        machine-readable flags, e.g. {@link #FLAG_CROP_UNRELIABLE}
 * @param payeeCheck   the organisation's payee check on {@code extracted.payeeName}
 *                     (MATCH / MISMATCH / UNREADABLE); null when the check is off,
 *                     lists no names, or nothing was read
 */
public record DetectedChequeItemDTO(
        UUID imageId,
        ChequeImageMetaDTO image,
        int page,
        ChequeBoundingBoxDTO box,
        String thumbnailUrl,
        ExtractedChequeDTO extracted,
        List<String> warnings,
        List<String> flags,
        com.datagami.rentaxis.domain.entity.enums.PayeeCheck payeeCheck
) {
    /** Without a payee check: the shape before PR #389. */
    public DetectedChequeItemDTO(UUID imageId, ChequeImageMetaDTO image, int page, ChequeBoundingBoxDTO box,
                                 String thumbnailUrl, ExtractedChequeDTO extracted, List<String> warnings,
                                 List<String> flags) {
        this(imageId, image, page, box, thumbnailUrl, extracted, warnings, flags, null);
    }

    /**
     * The cheque could not be cut out cleanly (no box, a box that is tiny, outside
     * the image or overlapping another cheque, or an image the server cannot
     * decode); {@code image} is the whole page instead of a crop.
     */
    public static final String FLAG_CROP_UNRELIABLE = "crop_unreliable";
}
