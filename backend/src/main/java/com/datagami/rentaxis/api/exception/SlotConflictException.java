package com.datagami.rentaxis.api.exception;

import java.time.Instant;

public class SlotConflictException extends RuntimeException {
    private final Instant nextAvailableSlot;

    public SlotConflictException(String message, Instant nextAvailableSlot) {
        super(message);
        this.nextAvailableSlot = nextAvailableSlot;
    }

    public Instant getNextAvailableSlot() {
        return nextAvailableSlot;
    }
}
