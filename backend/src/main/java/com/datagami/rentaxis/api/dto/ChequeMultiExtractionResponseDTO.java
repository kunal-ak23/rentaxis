package com.datagami.rentaxis.api.dto;

import java.util.List;

/**
 * {@code POST /api/v1/cheques/extract-many}: every cheque in one uploaded photo or
 * PDF, each with its own server-issued image.
 *
 * @param original the upload as stored. Not attachable when it is a PDF or holds
 *                 several cheques — attach the items' images instead
 * @param pages    one entry per page (a photo has one)
 * @param items    one entry per cheque found, in page then reading order; a page
 *                 where none was found still yields one item with {@code extracted}
 *                 null, so the operator can type it in
 * @param warnings warnings about the upload as a whole
 */
public record ChequeMultiExtractionResponseDTO(
        ChequeImageMetaDTO original,
        List<ChequeExtractionPageDTO> pages,
        List<DetectedChequeItemDTO> items,
        List<String> warnings
) {}
