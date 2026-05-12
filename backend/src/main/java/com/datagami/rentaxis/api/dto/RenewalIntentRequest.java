package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;

public record RenewalIntentRequest(@NotBlank String token) {}
