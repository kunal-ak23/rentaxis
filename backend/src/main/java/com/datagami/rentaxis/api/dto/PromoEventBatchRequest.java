package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Batched from the app on carousel dispose and on app background. */
public record PromoEventBatchRequest(
        @NotEmpty @Size(max = 50) List<Event> events) {

    public record Event(@NotNull UUID adId, @NotNull PromoEventType type) {
    }
}
