package com.datagami.rentaxis.api.dto;

/**
 * Where a cheque sits in its page image, normalised 0–1 of the page's width and
 * height: {@code x, y} is the top-left corner, {@code width, height} its size.
 * As the extractor reported it — a box the server did not trust is still shown.
 */
public record ChequeBoundingBoxDTO(double x, double y, double width, double height) {}
