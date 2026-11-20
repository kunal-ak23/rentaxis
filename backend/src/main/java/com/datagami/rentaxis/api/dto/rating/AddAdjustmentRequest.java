package com.datagami.rentaxis.api.dto.rating;

/** Manual adjustment: points -30..30 (non-zero) and a reason of 1-500 characters (validated in the service). */
public record AddAdjustmentRequest(int points, String reason) {}
