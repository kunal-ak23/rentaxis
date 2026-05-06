package com.datagami.rentaxis.core.email.api;

/**
 * Typed request body for PUT /api/v1/email/preferences.
 * All fields are nullable so callers may omit fields they don't want to change.
 */
public record EmailPreferencesUpdateRequest(Boolean marketingEnabled) {
}
