package com.datagami.rentaxis.api.dto;

/**
 * Demo feedback 2026-09-29: the parking counts on a property's Overview, in one
 * round trip instead of paging every spot. {@code total} is the active spots,
 * {@code assigned} the active ones held by an approved booking, {@code free}
 * the rest; {@code inactive} (deactivated spots) is reported apart and in none
 * of the other three.
 */
public record ParkingSummaryDTO(long total, long assigned, long free, long inactive) {
}
