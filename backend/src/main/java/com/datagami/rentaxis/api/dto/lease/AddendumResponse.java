package com.datagami.rentaxis.api.dto.lease;

/** The addendum written, and the lease and register re-read after it posted. */
public record AddendumResponse(LeaseAddendumDTO addendum, PostLeaseResponse posting) {
}
