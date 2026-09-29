package com.datagami.rentaxis.api.dto;

/**
 * One page of an upload: the photo itself, or one page of a PDF.
 *
 * @param page       1-based page number (always 1 for a photo)
 * @param previewUrl a small upright JPEG data URL of the whole page, for comparing
 *                   a crop with the original; null when the server cannot decode
 *                   the image (HEIC/HEIF)
 */
public record ChequeExtractionPageDTO(int page, String previewUrl) {}
