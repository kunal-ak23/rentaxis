package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ExtractedChequeDTO;

import java.util.List;

public interface ChequeExtractor {

    ExtractionResult extract(byte[] imageBytes, String contentType);

    record ExtractionResult(
            ExtractedChequeDTO extracted,
            List<String> warnings
    ) {}

    // ------------------------------------------------------------------
    // Multi-cheque: one image may hold several cheques.
    // ------------------------------------------------------------------

    /**
     * Every cheque found in one image, each with where it sits in the image.
     *
     * <p>The default asks {@link #extract} for a single cheque and wraps it, with no
     * box, so an extractor that cannot see several cheques (the unavailable
     * fallback, a test fake) still answers: one cheque, or none and its warnings.</p>
     */
    default MultiExtractionResult extractAll(byte[] imageBytes, String contentType) {
        ExtractionResult single = extract(imageBytes, contentType);
        List<String> warnings = single.warnings() == null ? List.of() : single.warnings();
        if (single.extracted() == null) {
            return new MultiExtractionResult(List.of(), warnings);
        }
        return new MultiExtractionResult(List.of(new DetectedCheque(single.extracted(), null, warnings)), List.of());
    }

    /**
     * Where a cheque sits in the image, normalised to the image: {@code x, y} is the
     * top-left corner and {@code width, height} its size, each 0–1 of the image's
     * own width or height. Not validated here — the cropper decides what it trusts.
     */
    record BoundingBox(double x, double y, double width, double height) {}

    /** One cheque found in an image. {@code box} is null when the extractor gave none. */
    record DetectedCheque(ExtractedChequeDTO extracted, BoundingBox box, List<String> warnings) {}

    /** All cheques found in one image, plus warnings about the image as a whole. */
    record MultiExtractionResult(List<DetectedCheque> cheques, List<String> warnings) {}
}
