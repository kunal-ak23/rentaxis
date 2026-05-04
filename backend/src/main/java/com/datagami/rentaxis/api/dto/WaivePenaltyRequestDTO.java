package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;

public record WaivePenaltyRequestDTO(@NotBlank String reason) {}
