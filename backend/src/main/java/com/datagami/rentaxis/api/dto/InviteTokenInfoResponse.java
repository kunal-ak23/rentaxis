package com.datagami.rentaxis.api.dto;

import java.time.Instant;

public record InviteTokenInfoResponse(String email, String name, Instant expiresAt) {}
