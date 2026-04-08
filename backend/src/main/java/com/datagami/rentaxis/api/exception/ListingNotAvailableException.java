package com.datagami.rentaxis.api.exception;

/**
 * Thrown when a listing exists but is not in a state where the requested
 * operation (e.g. expressing interest) is permitted. Maps to HTTP 400.
 */
public class ListingNotAvailableException extends BusinessRuleViolationException {
    public ListingNotAvailableException(String message) {
        super(message);
    }
}
