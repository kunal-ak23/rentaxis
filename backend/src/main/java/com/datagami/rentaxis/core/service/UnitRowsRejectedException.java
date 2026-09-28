package com.datagami.rentaxis.core.service;

import java.util.List;

/**
 * Break-it R3 ops3 F1/F2: the CSV unit upload refused one or more rows. Nothing was
 * saved; {@link #getErrors()} holds one "Row N: …" line per reason, the shape
 * {@code POST /units/bulk} already returned for rows it could not parse.
 */
public class UnitRowsRejectedException extends RuntimeException {

    private final List<String> errors;

    public UnitRowsRejectedException(List<String> errors) {
        super("CSV contains invalid rows");
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
