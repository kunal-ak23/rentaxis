package com.datagami.rentaxis.api.dto;

import java.util.UUID;

public record RenewalIntentResponse(String intent, UUID leaseId, String redirectTo) {}
