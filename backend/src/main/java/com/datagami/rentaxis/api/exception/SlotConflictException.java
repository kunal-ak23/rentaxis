package com.datagami.rentaxis.api.exception;

import java.time.Instant;

public class SlotConflictException extends RuntimeException {
    private final Instant nextAvailableSlot;
    /** Break-it R3 ops3 F7/F9: an optional client-translatable key (Common.errors.<code>); null = message only. */
    private final String code;

    public SlotConflictException(String message, Instant nextAvailableSlot) {
        this(message, nextAvailableSlot, null);
    }

    public SlotConflictException(String message, Instant nextAvailableSlot, String code) {
        super(message);
        this.nextAvailableSlot = nextAvailableSlot;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public Instant getNextAvailableSlot() {
        return nextAvailableSlot;
    }
}
