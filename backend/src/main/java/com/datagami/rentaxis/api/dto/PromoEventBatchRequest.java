package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Batched from the app on carousel dispose and on app background.
 *
 * <p>{@code @Valid} is load-bearing: Bean Validation does not descend into
 * collection elements without it, so without it {@link Event}'s {@code @NotNull}
 * constraints are dead code and a body like
 * {@code {"events":[{"adId":null,"type":null}]}} reaches the service, where it
 * becomes a 500 from a NOT NULL violation instead of a 400.
 */
public record PromoEventBatchRequest(
        @NotEmpty @Valid @Size(max = 50) List<Event> events) {

    public record Event(@NotNull UUID adId, @NotNull PromoEventType type) {
    }
}
