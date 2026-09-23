package com.datagami.rentaxis.api.dto;

/** The public (link) answer: what was recorded and where to go. No lease id for an anonymous caller. */
public record RenewalIntentResponse(String intent, String redirectTo) {}
