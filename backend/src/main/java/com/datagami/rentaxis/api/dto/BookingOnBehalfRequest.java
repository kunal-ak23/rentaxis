package com.datagami.rentaxis.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Staff book a bay or amenity for a renter (spec 2026-11-19 §4); the renter must hold a live lease on the unit. */
public record BookingOnBehalfRequest(
        @Valid @NotNull BookingCreateRequest booking,
        @NotNull UUID renterUserId) {
}
